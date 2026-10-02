package com.achintha.orderservice.scheduler;

import com.achintha.orderservice.order.DeadlineType;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderAction;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.order.OrderStateMachine;
import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fires due order timers every minute (section 6.4). ShedLock (JDBC) lets one replica run at a time; on top of that
 * every order is claimed in its own transaction with {@code SELECT ... FOR UPDATE SKIP LOCKED} and re-checked, so two
 * instances (or a run overlapping a user action) can never process the same order twice. Handlers are idempotent:
 * a timer that no longer applies (the order moved on) is simply not due any more.
 *
 * <ul>
 *   <li>{@code MERCHANT_RESPONSE}: {@code EXPIRED_MERCHANT} (release, merchant penalty hint)</li>
 *   <li>{@code CUSTOMER_CONFIRMATION}: {@code EXPIRED_CUSTOMER} (release, customer -1)</li>
 *   <li>{@code PAYMENT_SUBMISSION}: {@code EXPIRED_PAYMENT} (release, customer -1)</li>
 *   <li>{@code PAYMENT_VERIFICATION}, {@code SHIP_BY}: flag, penalty hint, keep open</li>
 *   <li>{@code AUTO_COMPLETE}: {@code COMPLETED} 7 days after shipping</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderDeadlineScheduler {

    private final OrderRepository orderRepository;
    private final OrderStateMachine stateMachine;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;

    public OrderDeadlineScheduler(OrderRepository orderRepository, OrderStateMachine stateMachine,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  @Value("${app.scheduling.order-deadlines.batch-size:100}") int batchSize) {
        this.orderRepository = orderRepository;
        this.stateMachine = stateMachine;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduling.order-deadlines.interval:PT1M}")
    @SchedulerLock(name = "order-service.orderDeadlines", lockAtMostFor = "PT5M")
    public void scheduledRun() {
        runOnce();
    }

    /** @return the number of orders this run handled */
    public int runOnce() {
        Instant now = clock.instant();
        List<UUID> due = orderRepository.findDueIds(now, batchSize);
        int handled = 0;
        for (UUID id : due) {
            try {
                Boolean done = transactions.execute(tx -> handle(id, now));
                if (Boolean.TRUE.equals(done)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                // Remote side effect failed (e.g. product-service down) or a user action won the race: retried
                // on the next run, nothing was changed
                log.warn("Deadline of order {} not processed this run: {}", id, e.getMessage());
            }
        }
        return handled;
    }

    private boolean handle(UUID id, Instant now) {
        Optional<Order> claimed = orderRepository.lockDue(id, now);
        if (claimed.isEmpty()) {
            return false; // no longer due, or another instance has it
        }
        Order order = claimed.get();
        DeadlineType type = order.getDeadlineType();
        Actor system = Actor.system();
        switch (type) {
            case MERCHANT_RESPONSE -> {
                order.setReasonCode("MERCHANT_RESPONSE_TIMEOUT");
                stateMachine.apply(order, OrderAction.EXPIRE_MERCHANT, system);
            }
            case CUSTOMER_CONFIRMATION -> {
                order.setReasonCode("CUSTOMER_CONFIRMATION_TIMEOUT");
                stateMachine.apply(order, OrderAction.EXPIRE_CUSTOMER, system);
            }
            case PAYMENT_SUBMISSION -> {
                order.setReasonCode("PAYMENT_TIMEOUT");
                stateMachine.apply(order, OrderAction.EXPIRE_PAYMENT, system);
            }
            case PAYMENT_VERIFICATION, SHIP_BY -> stateMachine.flagOverdue(order);
            case AUTO_COMPLETE -> stateMachine.apply(order, OrderAction.AUTO_COMPLETE, system);
        }
        log.info("Order {}: {} deadline handled, now {}", order.getPublicId(), type, order.getStatus());
        return true;
    }
}
