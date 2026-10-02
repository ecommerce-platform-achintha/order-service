package com.achintha.orderservice.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer-side de-duplication by {@code eventId} ({@code processed_events}). Call {@link #markProcessed} in the same
 * transaction as the handler's changes and skip the event when it returns {@code false}.
 */
@Component
@RequiredArgsConstructor
public class ProcessedEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** @return {@code true} the first time this consumer sees the event, {@code false} for a duplicate */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        int inserted = jdbcTemplate.update("""
                insert into processed_events (event_id, consumer, processed_at) values (?, ?, ?)
                on conflict (event_id, consumer) do nothing
                """, eventId, consumer, Timestamp.from(clock.instant()));
        return inserted == 1;
    }
}
