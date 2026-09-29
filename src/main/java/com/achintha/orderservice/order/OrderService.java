package com.achintha.orderservice.order;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.event.OrderEvent;
import com.achintha.orderservice.event.OrderEventType;
import com.achintha.orderservice.exception.InsufficientStockException;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.product.ProductResponse;
import com.achintha.orderservice.product.ProductServiceGateway;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceGateway productService;
    private final TransactionTemplate transactionTemplate;
    // Delivered to OrderEventPublisher after the surrounding transaction commits
    private final ApplicationEventPublisher events;

    /**
     * Prices and stock-checks every item against product-service, saves the order as PENDING, then decrements
     * inventory and ends in CONFIRMED or FAILED. Deliberately not one transaction: no DB transaction is held open
     * across the remote calls, and the PENDING row exists before stock is touched.
     *
     * <p>Publishes OrderCreated once the PENDING order is saved, then OrderConfirmed or OrderFailed.
     */
    public OrderResponse create(CreateOrderRequest request) {
        Order order = new Order(request.userId());
        for (Map.Entry<UUID, Integer> line : mergeQuantities(request.items()).entrySet()) {
            ProductResponse product = productService.getProduct(line.getKey());
            int requested = line.getValue();
            if (requested > product.quantityAvailable()) {
                throw new InsufficientStockException(product.id(), requested, product.quantityAvailable());
            }
            order.addItem(new OrderItem(product.id(), product.name(), product.price(), requested));
        }
        order = orderRepository.save(order);
        events.publishEvent(OrderEvent.of(OrderEventType.ORDER_CREATED, order));

        boolean reserved = reserveStock(order);
        return complete(order.getId(), order.getItems(), reserved);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listByUser(UUID userId, Pageable pageable) {
        return PageResponse.from(orderRepository.findByUserId(userId, pageable).map(OrderResponse::from));
    }

    /** PENDING or CONFIRMED -> CANCELLED; 409 from any other status. */
    @Transactional
    public OrderResponse cancel(UUID id) {
        Order order = find(id);
        order.cancel();
        Order saved = orderRepository.saveAndFlush(order);
        events.publishEvent(OrderEvent.of(OrderEventType.ORDER_CANCELLED, saved));
        return OrderResponse.from(saved);
    }

    /** The same product listed twice becomes one line, so its stock check sees the combined quantity. */
    private static Map<UUID, Integer> mergeQuantities(List<CreateOrderRequest.Item> items) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        items.forEach(item -> quantities.merge(item.productId(), item.quantity(), Integer::sum));
        return quantities;
    }

    /**
     * Decrements stock for every item. If one fails, the decrements already applied are put back so a FAILED order
     * doesn't keep stock out of circulation.
     */
    private boolean reserveStock(Order order) {
        List<OrderItem> decremented = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            try {
                productService.adjustInventory(item.getProductId(), -item.getQuantity());
                decremented.add(item);
            } catch (RuntimeException e) {
                log.warn("Inventory decrement failed for order {} product {}: {}", order.getId(),
                        item.getProductId(), e.getMessage());
                releaseStock(order.getId(), decremented);
                return false;
            }
        }
        return true;
    }

    /** Best effort: a failure here is logged for manual reconciliation rather than failing the request. */
    private void releaseStock(UUID orderId, List<OrderItem> items) {
        for (OrderItem item : items) {
            try {
                productService.adjustInventory(item.getProductId(), item.getQuantity());
            } catch (RuntimeException e) {
                log.error("Could not restock {} x product {} for order {}; needs manual reconciliation",
                        item.getQuantity(), item.getProductId(), orderId, e);
            }
        }
    }

    /** PENDING -> CONFIRMED/FAILED, unless the order was cancelled while the decrement was in flight. */
    private OrderResponse complete(UUID orderId, List<OrderItem> items, boolean stockReserved) {
        boolean cancelledMeanwhile;
        try {
            cancelledMeanwhile = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                Order current = find(orderId);
                if (current.getStatus() != OrderStatus.PENDING) {
                    return true;
                }
                if (stockReserved) {
                    current.confirm();
                } else {
                    current.fail();
                }
                orderRepository.saveAndFlush(current);
                events.publishEvent(OrderEvent.of(
                        stockReserved ? OrderEventType.ORDER_CONFIRMED : OrderEventType.ORDER_FAILED, current));
                return false;
            }));
        } catch (ObjectOptimisticLockingFailureException e) {
            cancelledMeanwhile = true; // a concurrent cancel committed first
        }
        if (cancelledMeanwhile && stockReserved) {
            releaseStock(orderId, items);
        }
        return transactionTemplate.execute(status -> OrderResponse.from(find(orderId)));
    }

    private Order find(UUID id) {
        return orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order not found: " + id));
    }
}
