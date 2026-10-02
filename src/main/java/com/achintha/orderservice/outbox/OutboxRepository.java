package com.achintha.orderservice.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Oldest unpublished messages, row-locked so that a second relay (another replica, should ShedLock ever be off)
     * skips them instead of publishing them twice.
     */
    @Query(value = """
            select * from outbox
            where published_at is null
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxMessage> lockUnpublished(@Param("limit") int limit);

    List<OutboxMessage> findAllByAggregateIdOrderByIdAsc(UUID aggregateId);
}
