package com.achintha.orderservice.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Orders. Every customer- and merchant-side lookup is scoped by the caller's id from the JWT (BOLA, section 3.4):
 * {@code ...AndCustomerId} / {@code ...AndStoreId}. A foreign id simply is not found (404).
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByPublicIdAndCustomerId(String publicId, UUID customerId);

    Optional<Order> findByPublicIdAndStoreId(String publicId, UUID storeId);

    Optional<Order> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    Page<Order> findAllByCustomerId(UUID customerId, Pageable pageable);

    Page<Order> findAllByCustomerIdAndStatusIn(UUID customerId, Collection<OrderStatus> statuses, Pageable pageable);

    Page<Order> findAllByStoreId(UUID storeId, Pageable pageable);

    Page<Order> findAllByStoreIdAndStatusIn(UUID storeId, Collection<OrderStatus> statuses, Pageable pageable);

    Page<Order> findAllByNeedsAdminResolutionTrueAndStatusIn(Collection<OrderStatus> statuses, Pageable pageable);

    Page<Order> findAllByStatusIn(Collection<OrderStatus> statuses, Pageable pageable);

    long countByCustomerIdAndStatusIn(UUID customerId, Collection<OrderStatus> statuses);

    List<Order> findAllByCustomerIdAndStatusIn(UUID customerId, Collection<OrderStatus> statuses);

    List<Order> findAllByStoreIdAndStatusIn(UUID storeId, Collection<OrderStatus> statuses);

    List<Order> findAllByCheckoutGroupIdOrderByPlacedAtAsc(UUID checkoutGroupId);

    /** Whether the customer ever ordered from the store (a merchant may only block its own customers). */
    boolean existsByStoreIdAndCustomerPublicId(UUID storeId, String customerPublicId);

    Optional<Order> findFirstByStoreIdAndCustomerPublicId(UUID storeId, String customerPublicId);

    Optional<Order> findFirstByCustomerPublicId(String customerPublicId);

    /** Ids of orders whose timer is due (no lock; each one is then claimed with {@link #lockDue}). */
    @Query(value = """
            select id from orders
            where deadline_at is not null and deadline_at <= :now
            order by deadline_at
            limit :limit
            """, nativeQuery = true)
    List<UUID> findDueIds(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Claims one due order: row-locked {@code FOR UPDATE SKIP LOCKED}, so a second scheduler instance skips it
     * instead of processing it twice. Empty if it is no longer due or another instance holds it.
     */
    @Query(value = """
            select * from orders
            where id = :id and deadline_at is not null and deadline_at <= :now
            for update skip locked
            """, nativeQuery = true)
    Optional<Order> lockDue(@Param("id") UUID id, @Param("now") Instant now);
}
