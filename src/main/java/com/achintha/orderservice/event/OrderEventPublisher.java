package com.achintha.orderservice.event;

import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderItem;
import com.achintha.orderservice.outbox.OutboxMessage;
import com.achintha.orderservice.outbox.OutboxRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Records {@code order-events} in the outbox, inside the caller's transaction ({@code MANDATORY}): the event is stored
 * if and only if the change commits. {@link com.achintha.orderservice.outbox.OutboxRelay} publishes it to Kafka
 * afterwards (at-least-once), keyed by the order's UUID so one order's events stay in order. Nothing is ever sent to
 * Kafka directly from business code (this replaces the old fire-and-forget publisher).
 */
@Component
public class OrderEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String topic;

    public OrderEventPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper, Clock clock,
                               @Value("${app.kafka.topics.order-events}") String topic) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.topic = topic;
    }

    /** Optional fields of one event; everything else is taken from the order. */
    public record Details(String previousStatus, String terminalStatus, String merchantPenalty,
                          String customerPenalty, String complaintPublicId, String decision,
                          String flaggedReferencePublicId, String resolution, String trackingNumber) {

        public static final Details NONE = new Details(null, null, null, null, null, null, null, null, null);

        public static Details previous(String previousStatus) {
            return new Details(previousStatus, null, null, null, null, null, null, null, null);
        }

        public Details terminal(String status) {
            return new Details(previousStatus, status, merchantPenalty, customerPenalty, complaintPublicId, decision,
                    flaggedReferencePublicId, resolution, trackingNumber);
        }

        public Details merchantPenalty(String penalty) {
            return new Details(previousStatus, terminalStatus, penalty, customerPenalty, complaintPublicId, decision,
                    flaggedReferencePublicId, resolution, trackingNumber);
        }

        public Details customerPenalty(String penalty) {
            return new Details(previousStatus, terminalStatus, merchantPenalty, penalty, complaintPublicId, decision,
                    flaggedReferencePublicId, resolution, trackingNumber);
        }

        public Details complaint(String publicId, String decisionValue) {
            return new Details(previousStatus, terminalStatus, merchantPenalty, customerPenalty, publicId,
                    decisionValue, flaggedReferencePublicId, resolution, trackingNumber);
        }

        public Details flagged(String publicId) {
            return new Details(previousStatus, terminalStatus, merchantPenalty, customerPenalty, complaintPublicId,
                    decision, publicId, resolution, trackingNumber);
        }

        public Details resolution(String value) {
            return new Details(previousStatus, terminalStatus, merchantPenalty, customerPenalty, complaintPublicId,
                    decision, flaggedReferencePublicId, value, trackingNumber);
        }

        public Details tracking(String number) {
            return new Details(previousStatus, terminalStatus, merchantPenalty, customerPenalty, complaintPublicId,
                    decision, flaggedReferencePublicId, resolution, number);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrderEvent publish(Order order, OrderEventType type, Details details, String checkoutPublicId) {
        List<OrderEvent.Line> lines = order.getItems().stream().map(OrderEventPublisher::line).toList();
        OrderEvent event = new OrderEvent(UUID.randomUUID(), type, clock.instant(), order.getId(),
                order.getPublicId(), checkoutPublicId, order.getStoreId(), order.getStorePublicId(),
                order.getCustomerId(), order.getCustomerPublicId(), order.getStatus().name(),
                details.previousStatus(), details.terminalStatus(), order.getReasonCode() != null
                        ? order.getReasonCode() : order.getReason(),
                order.getPaymentMethod().name(), order.getItemsTotal(), order.getCourierCharge(),
                order.getOtherChargesTotal(), order.getQuoteDiscount(), order.getGrandTotal(), order.getPlacedAt(),
                order.getQuoteRevision() == 0 ? null : order.getQuoteRevision(), details.merchantPenalty(),
                details.customerPenalty(), lines, order.getCourierCode(), details.trackingNumber(),
                order.getDeliveryFailureType() == null ? null : order.getDeliveryFailureType().name(),
                details.complaintPublicId(), details.decision(), details.flaggedReferencePublicId(),
                details.resolution());
        append(event);
        return event;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrderEvent publish(Order order, OrderEventType type, Details details) {
        return publish(order, type, details, null);
    }

    private static OrderEvent.Line line(OrderItem item) {
        return new OrderEvent.Line(item.getVariantId(), item.getVariantPublicId(), item.getItemPublicId(),
                item.getQuantity());
    }

    private void append(OrderEvent event) {
        OutboxMessage message = new OutboxMessage();
        message.setEventId(event.eventId());
        message.setAggregateId(event.orderId());
        message.setEventType(event.eventType().name());
        message.setTopic(topic);
        message.setMessageKey(event.orderId().toString());
        message.setPayload(objectMapper.writeValueAsString(event));
        message.setCreatedAt(event.occurredAt());
        outboxRepository.save(message);
    }
}
