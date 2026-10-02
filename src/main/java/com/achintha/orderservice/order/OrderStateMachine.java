package com.achintha.orderservice.order;

import com.achintha.orderservice.audit.AuditService;
import com.achintha.orderservice.customer.CodPrivilegeService;
import com.achintha.orderservice.customer.CustomerScoreService;
import com.achintha.orderservice.customer.ScoreEventType;
import com.achintha.orderservice.event.OrderEventPublisher;
import com.achintha.orderservice.event.OrderEventPublisher.Details;
import com.achintha.orderservice.event.OrderEventType;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.InvalidOrderStateException;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.platform.SettingKeys;
import com.achintha.orderservice.ports.NotificationPort;
import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place an order's status changes (section 6.2). Every transition goes through {@link #apply}, which
 *
 * <ol>
 *   <li>checks the actor: the order's own customer, the order's store (merchant, or assistant with the action's
 *       permission), an admin, or the system. A foreign order is reported as not found (404), never 403;</li>
 *   <li>checks the current state (409 {@code INVALID_ORDER_STATE}), that a user is not acting after a running
 *       deadline has passed (409 {@code DEADLINE_PASSED}) and the re-quote limit;</li>
 *   <li>changes the status, stage timestamps and the next deadline, and flushes: the {@code @Version} check runs
 *       before any remote side effect, so a timer and a user action can never both win;</li>
 *   <li>applies the effects: stock hold (adjust, commit or release), customer score, COD refusal count;</li>
 *   <li>writes the {@code order-events} event to the outbox, an audit row for merchant, assistant and admin actions,
 *       and a (mock) notification.</li>
 * </ol>
 *
 * Everything runs in the caller's transaction: if a remote call fails, nothing is changed.
 */
@Slf4j
@Component
public class OrderStateMachine {

    /** Deadlines a user may not act past (the scheduler is about to expire the order). */
    private static final Set<DeadlineType> EXPIRING = EnumSet.of(DeadlineType.MERCHANT_RESPONSE,
            DeadlineType.CUSTOMER_CONFIRMATION, DeadlineType.PAYMENT_SUBMISSION);

    private final OrderRepository orderRepository;
    private final OrderTimers timers;
    private final StockHolds stockHolds;
    private final CustomerScoreService scoreService;
    private final CodPrivilegeService codService;
    private final OrderEventPublisher events;
    private final AuditService auditService;
    private final NotificationPort notifications;
    private final PlatformSettings settings;
    private final Clock clock;
    private final Duration overdueHoldExtension;

    public OrderStateMachine(OrderRepository orderRepository, OrderTimers timers, StockHolds stockHolds,
                             CustomerScoreService scoreService, CodPrivilegeService codService,
                             OrderEventPublisher events, AuditService auditService, NotificationPort notifications,
                             PlatformSettings settings, Clock clock,
                             @Value("${app.orders.overdue-hold-extension:P30D}") Duration overdueHoldExtension) {
        this.orderRepository = orderRepository;
        this.timers = timers;
        this.stockHolds = stockHolds;
        this.scoreService = scoreService;
        this.codService = codService;
        this.events = events;
        this.auditService = auditService;
        this.notifications = notifications;
        this.settings = settings;
        this.clock = clock;
        this.overdueHoldExtension = overdueHoldExtension;
    }

    /**
     * Applies {@code action} to {@code order}. Action-specific data (quote lines, rejection reason, delivery failure
     * type, ...) is set on the order by the caller before; this method validates, transitions and applies effects.
     *
     * @return the status before the transition
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderStatus apply(Order order, OrderAction action, Actor actor) {
        checkActor(order, action, actor);
        OrderStatus from = order.getStatus();
        if (!action.from().contains(from)) {
            throw new InvalidOrderStateException("Order " + order.getPublicId() + " is " + from
                    + ": " + action.name().toLowerCase().replace('_', ' ') + " is not possible");
        }
        Instant now = clock.instant();
        checkDeadline(order, action, now);
        if (action == OrderAction.MERCHANT_QUOTE && from == OrderStatus.AWAITING_CUSTOMER_CONFIRMATION) {
            int maxRevisions = settings.intValue(SettingKeys.MAX_QUOTE_REVISIONS);
            if (order.getQuoteRevision() - 1 >= maxRevisions) {
                throw new ConflictException(ErrorCode.QUOTE_REVISION_LIMIT_REACHED,
                        "The quote was already revised " + maxRevisions + " times");
            }
        }

        OrderStatus to = action.target(order);
        order.setStatus(to);
        order.setUpdatedAt(now);
        stageAndDeadline(order, action, to, now);
        // Optimistic lock check before any remote effect: the loser of a race fails here (409), untouched
        orderRepository.saveAndFlush(order);

        Details details = Details.previous(from.name());
        stockEffect(order, action, from, to);
        details = scoreEffect(order, action, to, details);
        if (to == OrderStatus.DELIVERY_FAILED && order.getPaymentMethod() == PaymentMethod.COD) {
            boolean suspended = codService.recordRefusal(order.getCustomerId(), order.getCustomerPublicId(),
                    order.getId());
            if (suspended) {
                notifications.notifyCustomer(order.getCustomerId(), "COD_SUSPENDED", order.getPublicId());
            }
        }
        publish(order, action, to, details);
        audit(order, action, actor, from, to);
        notify(order, to);
        return from;
    }

    /**
     * A missed ship-by or payment-verification deadline (section 13.3): the order stays open, is flagged
     * ({@code lateShipment} / {@code lateVerification}) for admins, and the merchant penalty hint is published. The
     * timer stops (it fires once).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void flagOverdue(Order order) {
        DeadlineType type = order.getDeadlineType();
        Instant now = clock.instant();
        Details details = Details.previous(order.getStatus().name());
        OrderEventType eventType;
        if (type == DeadlineType.SHIP_BY && order.getStatus() == OrderStatus.READY_TO_SHIP) {
            order.setLateShipment(true);
            eventType = OrderEventType.OrderShipmentOverdue;
            details = details.merchantPenalty("LATE_SHIPMENT");
        } else if (type == DeadlineType.PAYMENT_VERIFICATION && order.getStatus() == OrderStatus.PAYMENT_SUBMITTED) {
            order.setLateVerification(true);
            eventType = OrderEventType.OrderVerificationOverdue;
            details = details.merchantPenalty("LATE_VERIFICATION");
        } else {
            throw new IllegalStateException("Order " + order.getPublicId() + " has no overdue " + type);
        }
        order.clearDeadline();
        order.setUpdatedAt(now);
        orderRepository.saveAndFlush(order);
        if (eventType == OrderEventType.OrderVerificationOverdue) {
            // Still waiting for the merchant: keep the hold alive past product-service's safety net
            stockHolds.adjust(order, now.plus(overdueHoldExtension));
        }
        events.publish(order, eventType, details);
        auditService.record(Actor.system(), eventType.name(), AuditService.TARGET_ORDER, order.getPublicId(),
                null, Map.of("lateShipment", order.isLateShipment(), "lateVerification", order.isLateVerification()),
                null);
        notifications.notifyAdmins("ORDER_OVERDUE", order.getPublicId() + " " + type);
        notifications.notifyStore(order.getStoreId(), "ORDER_OVERDUE", order.getPublicId());
    }

    /** The merchant was banned with this order open (D4 day 14): an admin must resolve it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void flagForAdminResolution(Order order) {
        if (order.getStatus().isTerminal() || order.isNeedsAdminResolution()) {
            return;
        }
        order.setNeedsAdminResolution(true);
        order.setUpdatedAt(clock.instant());
        orderRepository.saveAndFlush(order);
        events.publish(order, OrderEventType.OrderNeedsAdminResolution, Details.previous(order.getStatus().name()));
        notifications.notifyAdmins("ORDER_NEEDS_RESOLUTION", order.getPublicId());
    }

    // ------------------------------------------------------------------------------------------------- guards

    private static void checkActor(Order order, OrderAction action, Actor actor) {
        switch (action.performer()) {
            case CUSTOMER -> {
                if (!actor.isCustomer() || !order.getCustomerId().equals(actor.id())) {
                    throw notFoundOrDenied(order, actor);
                }
            }
            case MERCHANT -> {
                if (!actor.isMerchantSide() || !order.getStoreId().equals(actor.storeId())) {
                    throw notFoundOrDenied(order, actor);
                }
                if (action.permission() != null && !actor.hasPermission(action.permission())) {
                    throw ApiException.forbidden(ErrorCode.ACCESS_DENIED,
                            "Missing permission " + action.permission());
                }
            }
            case ADMIN -> {
                if (!actor.isAdmin()) {
                    throw ApiException.forbidden(ErrorCode.ACTION_NOT_PERMITTED, "Admins only");
                }
            }
            case SYSTEM -> {
                if (!actor.isSystem()) {
                    throw ApiException.forbidden(ErrorCode.ACTION_NOT_PERMITTED, "System action");
                }
            }
        }
    }

    /** Someone else's order is "not found" to customers and stores (BOLA); other roles get a plain 403. */
    private static ApiException notFoundOrDenied(Order order, Actor actor) {
        boolean ownsSomething = (actor.isCustomer() && order.getCustomerId().equals(actor.id()))
                || (actor.isMerchantSide() && order.getStoreId().equals(actor.storeId()));
        if (!ownsSomething && (actor.isCustomer() || actor.isMerchantSide())) {
            return new NotFoundException("Order not found");
        }
        return ApiException.forbidden(ErrorCode.ACTION_NOT_PERMITTED, "This action is not available to you");
    }

    private static void checkDeadline(Order order, OrderAction action, Instant now) {
        boolean userAction = action.performer() == OrderAction.Performer.CUSTOMER
                || action.performer() == OrderAction.Performer.MERCHANT;
        if (userAction && order.getDeadlineAt() != null && EXPIRING.contains(order.getDeadlineType())
                && !now.isBefore(order.getDeadlineAt())) {
            throw new ConflictException(ErrorCode.DEADLINE_PASSED,
                    "The deadline for this step has passed; the order is being closed");
        }
    }

    // ------------------------------------------------------------------------------- state, timers, effects

    private void stageAndDeadline(Order order, OrderAction action, OrderStatus to, Instant now) {
        switch (action) {
            case MERCHANT_QUOTE -> {
                order.setQuotedAt(now);
                order.setQuoteRevision(order.getQuoteRevision() + 1);
                order.setDeadline(DeadlineType.CUSTOMER_CONFIRMATION, timers.customerConfirmation(now));
            }
            case CUSTOMER_CONFIRM -> {
                order.setConfirmedAt(now);
                if (to == OrderStatus.READY_TO_SHIP) {
                    order.setReadyToShipAt(now);
                    order.setDeadline(DeadlineType.SHIP_BY, timers.shipBy(now));
                } else {
                    Instant paymentDeadline = timers.paymentSubmission(now);
                    order.setPaymentDeadlineAt(paymentDeadline);
                    order.setDeadline(DeadlineType.PAYMENT_SUBMISSION, paymentDeadline);
                }
            }
            case PAYMENT_SUBMIT -> {
                order.setPaymentSubmittedAt(now);
                order.setDeadline(DeadlineType.PAYMENT_VERIFICATION, timers.paymentVerification(now));
            }
            case PAYMENT_VERIFY -> {
                order.setPaymentVerifiedAt(now);
                order.setReadyToShipAt(now);
                order.setDeadline(DeadlineType.SHIP_BY, timers.shipBy(now));
            }
            // Back to waiting for a payment, until the original payment deadline
            case PAYMENT_REJECT -> order.setDeadline(DeadlineType.PAYMENT_SUBMISSION, order.getPaymentDeadlineAt());
            case SHIP -> {
                order.setShippedAt(now);
                order.setDeadline(DeadlineType.AUTO_COMPLETE, timers.autoComplete(now));
            }
            default -> {
                // terminal transitions
            }
        }
        if (to.isTerminal()) {
            order.clearDeadline();
            order.setNeedsAdminResolution(false);
            if (to == OrderStatus.COMPLETED) {
                order.setCompletedAt(now);
            } else if (to == OrderStatus.DELIVERY_FAILED) {
                order.setDeliveryFailedAt(now);
            } else {
                order.setClosedAt(now);
            }
        }
    }

    private void stockEffect(Order order, OrderAction action, OrderStatus from, OrderStatus to) {
        Instant now = clock.instant();
        switch (action) {
            // The quote may only reduce lines; the hold follows, and lives as long as the customer's timer
            case MERCHANT_QUOTE -> stockHolds.adjust(order, order.getDeadlineAt());
            case CUSTOMER_CONFIRM -> {
                if (to == OrderStatus.READY_TO_SHIP) {
                    stockHolds.commit(order);
                } else {
                    stockHolds.adjust(order, order.getDeadlineAt());
                }
            }
            case PAYMENT_SUBMIT -> stockHolds.adjust(order, order.getDeadlineAt());
            case PAYMENT_VERIFY -> stockHolds.commit(order);
            case PAYMENT_REJECT -> {
                if (order.getDeadlineAt() != null && order.getDeadlineAt().isAfter(now)) {
                    stockHolds.adjust(order, order.getDeadlineAt());
                }
            }
            case ADMIN_FORCE_COMPLETE -> {
                if (OrderStatus.STOCK_HELD.contains(from)) {
                    stockHolds.commit(order);
                }
            }
            default -> {
                if (to.isTerminal() && to != OrderStatus.COMPLETED) {
                    stockHolds.releaseIfHeld(order);
                }
            }
        }
    }

    private Details scoreEffect(Order order, OrderAction action, OrderStatus to, Details details) {
        ScoreEventType type = switch (action) {
            case CUSTOMER_DECLINE -> ScoreEventType.QUOTE_DECLINED;
            case EXPIRE_CUSTOMER -> ScoreEventType.QUOTE_EXPIRED;
            case EXPIRE_PAYMENT -> ScoreEventType.PAYMENT_EXPIRED;
            // D7: +1 on completion (an admin force-complete is a resolution, not a customer merit)
            case MARK_RECEIVED, AUTO_COMPLETE -> ScoreEventType.ORDER_COMPLETED;
            default -> null;
        };
        if (type == null) {
            return details;
        }
        scoreService.apply(order.getCustomerId(), order.getId(), type);
        return switch (type) {
            case QUOTE_DECLINED -> details.customerPenalty("DECLINED");
            case QUOTE_EXPIRED, PAYMENT_EXPIRED -> details.customerPenalty("EXPIRED");
            default -> details;
        };
    }

    private void publish(Order order, OrderAction action, OrderStatus to, Details details) {
        OrderEventType type = switch (action) {
            case MERCHANT_QUOTE -> OrderEventType.OrderQuoted;
            case CUSTOMER_CONFIRM -> OrderEventType.OrderConfirmed;
            case PAYMENT_SUBMIT -> OrderEventType.OrderPaymentSubmitted;
            case PAYMENT_VERIFY -> OrderEventType.OrderPaymentVerified;
            case PAYMENT_REJECT -> OrderEventType.OrderPaymentRejected;
            case SHIP -> OrderEventType.OrderShipped;
            case DELIVERY_FAILED -> OrderEventType.OrderDeliveryFailed;
            default -> to == OrderStatus.COMPLETED ? OrderEventType.OrderCompleted : OrderEventType.OrderCancelled;
        };
        if (type == OrderEventType.OrderCancelled) {
            details = details.terminal(to.name());
        }
        if (action == OrderAction.EXPIRE_MERCHANT) {
            details = details.merchantPenalty("RESPONSE_TIMEOUT");
        }
        if (action == OrderAction.ADMIN_CLOSE || action == OrderAction.ADMIN_FORCE_COMPLETE) {
            details = details.resolution(action == OrderAction.ADMIN_CLOSE ? "CANCEL" : "FORCE_COMPLETE");
        }
        events.publish(order, type, details);
    }

    private void audit(Order order, OrderAction action, Actor actor, OrderStatus from, OrderStatus to) {
        if (actor.isCustomer()) {
            return;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", to.name());
        after.put("grandTotal", order.getGrandTotal());
        if (order.getReasonCode() != null) {
            after.put("reasonCode", order.getReasonCode());
        }
        boolean withReason = action == OrderAction.MERCHANT_REJECT || action == OrderAction.PAYMENT_REJECT
                || action == OrderAction.DELIVERY_FAILED || action == OrderAction.ADMIN_CLOSE
                || action == OrderAction.ADMIN_FORCE_COMPLETE;
        auditService.record(actor, action.name(), AuditService.TARGET_ORDER, order.getPublicId(),
                Map.of("status", from.name()), after, withReason ? order.getReason() : null);
    }

    private void notify(Order order, OrderStatus to) {
        switch (to) {
            case AWAITING_CUSTOMER_CONFIRMATION -> notifications.notifyCustomer(order.getCustomerId(),
                    "ORDER_QUOTED", order.getPublicId());
            case PAYMENT_SUBMITTED -> notifications.notifyStore(order.getStoreId(), "PAYMENT_TO_VERIFY",
                    order.getPublicId());
            case READY_TO_SHIP -> notifications.notifyStore(order.getStoreId(), "ORDER_READY_TO_SHIP",
                    order.getPublicId());
            case SHIPPED -> notifications.notifyCustomer(order.getCustomerId(), "ORDER_SHIPPED", order.getPublicId());
            default -> {
                if (to.isTerminal()) {
                    notifications.notifyCustomer(order.getCustomerId(), "ORDER_" + to.name(), order.getPublicId());
                    notifications.notifyStore(order.getStoreId(), "ORDER_" + to.name(), order.getPublicId());
                }
            }
        }
    }
}
