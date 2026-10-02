package com.achintha.orderservice.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.support.IntegrationTest;
import com.achintha.orderservice.support.Remote;
import com.achintha.orderservice.support.TestUser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Two scheduler instances running at the same moment (ShedLock bypassed: {@code runOnce} is called directly) never
 * process the same order twice: each order is claimed with {@code FOR UPDATE SKIP LOCKED} and re-checked.
 */
class SchedulerConcurrencyTest extends IntegrationTest {

    @Test
    void twoInstancesNeverDoubleProcess() throws Exception {
        Shop shop = shop();
        Variant v = variant(shop, "10.00", 1000);
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TestUser customer = customer();
            for (int j = 0; j < 3; j++) {
                orders.add(placeOrder(customer, v, 1, "COD"));
            }
        }
        clock.advance(Duration.ofHours(25));

        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Integer> instance = () -> {
            start.await();
            int handled = 0;
            int batch;
            while ((batch = scheduler.runOnce()) > 0) {
                handled += batch;
            }
            return handled;
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(instance);
            Future<Integer> second = pool.submit(instance);
            assertThat(first.get() + second.get()).isGreaterThanOrEqualTo(orders.size());
        }

        for (String order : orders) {
            assertThat(statusOf(order)).isEqualTo(OrderStatus.EXPIRED_MERCHANT);
            assertThat(eventTypes(order).stream().filter("OrderCancelled"::equals)).hasSize(1);
            assertThat(Remote.releaseCalls(order)).isEqualTo(1);
        }
    }
}
