package com.achintha.orderservice.events;

import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderAction;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.order.OrderStateMachine;
import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.outbox.ProcessedEventStore;
import com.achintha.orderservice.security.Actor;
import com.achintha.orderservice.security.SecurityStateCache;
import com.achintha.orderservice.security.UserStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one {@code user-events} message (idempotent via {@code processed_events}):
 * <ul>
 *   <li>{@code UserSecurityChanged} / {@code UserStatusChanged}: updates the security-state cache;</li>
 *   <li>customer {@code BANNED}: unconfirmed orders become {@code CANCELLED_BY_SYSTEM}, stock released, no penalty
 *       (section 3.2); confirmed orders continue;</li>
 *   <li>merchant {@code BANNED} (end of the grace period): remaining open orders are flagged
 *       {@code needsAdminResolution} (D4).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventHandler {

    public static final String CONSUMER = "order-service.user-events";

    private final ProcessedEventStore processedEvents;
    private final SecurityStateCache securityStateCache;
    private final OrderRepository orderRepository;
    private final OrderStateMachine stateMachine;

    /** @return {@code false} for a duplicate or an unusable message */
    @Transactional
    public boolean handle(UserEvent event) {
        if (event.eventId() == null || event.eventType() == null || event.userId() == null) {
            log.warn("Skipping user event without eventId/eventType/userId");
            return false;
        }
        UserStatus status = parse(event.status());
        if ("UserSecurityChanged".equals(event.eventType()) || "UserStatusChanged".equals(event.eventType())) {
            // Cache updates are harmless to repeat (versions never go backwards)
            securityStateCache.apply(event.userId(), event.tokenVersion(), status);
        }
        if (!processedEvents.markProcessed(event.eventId(), CONSUMER)) {
            return false;
        }
        if (!"UserStatusChanged".equals(event.eventType()) || status != UserStatus.BANNED) {
            return true;
        }
        if ("ROLE_CUSTOMER".equals(event.role())) {
            cancelUnconfirmedOrders(event);
        } else if ("ROLE_MERCHANT".equals(event.role()) && event.storeId() != null) {
            flagOpenOrders(event);
        }
        return true;
    }

    private void cancelUnconfirmedOrders(UserEvent event) {
        List<Order> orders = orderRepository.findAllByCustomerIdAndStatusIn(event.userId(), OrderStatus.UNCONFIRMED);
        for (Order order : orders) {
            order.setReasonCode("CUSTOMER_BANNED");
            stateMachine.apply(order, OrderAction.SYSTEM_CANCEL, Actor.system());
        }
        if (!orders.isEmpty()) {
            log.info("Customer {} banned: {} unconfirmed orders cancelled", event.publicId(), orders.size());
        }
    }

    private void flagOpenOrders(UserEvent event) {
        List<Order> orders = orderRepository.findAllByStoreIdAndStatusIn(event.storeId(), OrderStatus.OPEN);
        orders.forEach(stateMachine::flagForAdminResolution);
        if (!orders.isEmpty()) {
            log.info("Merchant {} banned: {} open orders need admin resolution", event.publicId(), orders.size());
        }
    }

    private static UserStatus parse(String status) {
        if (status == null) {
            return null;
        }
        try {
            return UserStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
