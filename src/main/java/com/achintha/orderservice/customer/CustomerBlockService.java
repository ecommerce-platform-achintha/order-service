package com.achintha.orderservice.customer;

import com.achintha.orderservice.audit.AuditService;
import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.TextSanitizer;
import com.achintha.orderservice.customer.CustomerDtos.BlockRequest;
import com.achintha.orderservice.customer.CustomerDtos.BlockResponse;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.security.AccountGuard;
import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store-customer blocks ({@code CUSTOMER_BLOCK}, section 6.5). Scoped by the JWT's store; a store can only block a
 * customer who ordered from it (others are "not found"). Checked at checkout.
 */
@Service
@RequiredArgsConstructor
public class CustomerBlockService {

    private final StoreCustomerBlockRepository blocks;
    private final OrderRepository orders;
    private final AccountGuard accountGuard;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<BlockResponse> list(Actor actor, Pageable pageable) {
        return PageResponse.from(blocks.findAllByStoreId(actor.storeId(), pageable), BlockResponse::from);
    }

    @Transactional
    public BlockResponse block(Actor actor, BlockRequest request) {
        accountGuard.requireMerchantCanAct(actor);
        Order order = orders.findFirstByStoreIdAndCustomerPublicId(actor.storeId(), request.customerPublicId())
                .orElseThrow(() -> new NotFoundException("Customer not found among this store's orders"));
        if (blocks.existsByStoreIdAndCustomerId(actor.storeId(), order.getCustomerId())) {
            throw new ConflictException(ErrorCode.CUSTOMER_ALREADY_BLOCKED, "This customer is already blocked");
        }
        String reason = TextSanitizer.cleanLine(request.reason());
        if (reason == null) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A reason is required");
        }
        StoreCustomerBlock block = new StoreCustomerBlock();
        block.setId(UUID.randomUUID());
        block.setStoreId(actor.storeId());
        block.setCustomerId(order.getCustomerId());
        block.setCustomerPublicId(order.getCustomerPublicId());
        block.setReason(reason);
        block.setBlockedBy(actor.publicId());
        block.setCreatedAt(clock.instant());
        blocks.save(block);
        auditService.record(actor, "CUSTOMER_BLOCKED", AuditService.TARGET_CUSTOMER_BLOCK,
                request.customerPublicId(), null, Map.of("blocked", true), reason);
        return BlockResponse.from(block);
    }

    @Transactional
    public void unblock(Actor actor, String customerPublicId, String reason) {
        accountGuard.requireMerchantCanAct(actor);
        StoreCustomerBlock block = blocks.findByStoreIdAndCustomerPublicId(actor.storeId(), customerPublicId)
                .orElseThrow(() -> new NotFoundException("This customer is not blocked"));
        blocks.delete(block);
        auditService.record(actor, "CUSTOMER_UNBLOCKED", AuditService.TARGET_CUSTOMER_BLOCK, customerPublicId,
                Map.of("blocked", true), Map.of("blocked", false), TextSanitizer.cleanLine(reason));
    }
}
