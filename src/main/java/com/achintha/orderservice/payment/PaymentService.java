package com.achintha.orderservice.payment;

import com.achintha.orderservice.audit.AuditService;
import com.achintha.orderservice.client.StoreServiceClient.BankAccount;
import com.achintha.orderservice.client.StoreServiceGateway;
import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.PublicIdGenerator;
import com.achintha.orderservice.common.TextSanitizer;
import com.achintha.orderservice.crypto.AccountNumberCipher;
import com.achintha.orderservice.event.OrderEventPublisher;
import com.achintha.orderservice.event.OrderEventPublisher.Details;
import com.achintha.orderservice.event.OrderEventType;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.InvalidOrderStateException;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderAction;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.order.OrderResponse;
import com.achintha.orderservice.order.OrderStateMachine;
import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.order.OrderViews;
import com.achintha.orderservice.order.OrderViews.Audience;
import com.achintha.orderservice.order.PaymentMethod;
import com.achintha.orderservice.payment.PaymentDtos.FlagReviewRequest;
import com.achintha.orderservice.payment.PaymentDtos.FlaggedReferenceResponse;
import com.achintha.orderservice.payment.PaymentDtos.PaymentRequest;
import com.achintha.orderservice.ports.NotificationPort;
import com.achintha.orderservice.security.AccountGuard;
import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bank-transfer payments (section 6.3). The platform never handles money: the customer reports a transfer, the
 * store (merchant or assistant with {@code PAYMENT_VERIFY}) verifies or rejects it.
 *
 * <p>Duplicate references (D9): the reference is normalised and the (reference, destination bank) pair is checked
 * across all stores. A duplicate is rejected with a generic message, and a {@code flagged_payment_references} row
 * plus a {@code PaymentReferenceFlagged} event are committed (in their own transaction) for the admin queue. A race
 * between two identical submissions is settled by the unique index and handled the same way.
 */
@Slf4j
@Service
public class PaymentService {

    /** Deliberately generic: a fraudster learns nothing about the earlier use of the reference. */
    static final String GENERIC_REJECTION =
            "The payment could not be accepted. Check the reference and bank details, or contact support.";

    private static final Set<PaymentStatus> SUCCESSFUL = EnumSet.of(PaymentStatus.SUBMITTED, PaymentStatus.VERIFIED);

    private final OrderRepository orderRepository;
    private final PaymentSubmissionRepository submissions;
    private final FlaggedPaymentReferenceRepository flags;
    private final OrderStateMachine stateMachine;
    private final OrderViews views;
    private final StoreServiceGateway storeService;
    private final AccountNumberCipher cipher;
    private final PublicIdGenerator publicIds;
    private final OrderEventPublisher events;
    private final AuditService auditService;
    private final NotificationPort notifications;
    private final AccountGuard accountGuard;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PaymentService(OrderRepository orderRepository, PaymentSubmissionRepository submissions,
                          FlaggedPaymentReferenceRepository flags, OrderStateMachine stateMachine, OrderViews views,
                          StoreServiceGateway storeService, AccountNumberCipher cipher, PublicIdGenerator publicIds,
                          OrderEventPublisher events, AuditService auditService, NotificationPort notifications,
                          AccountGuard accountGuard, PlatformTransactionManager transactionManager, Clock clock) {
        this.orderRepository = orderRepository;
        this.submissions = submissions;
        this.flags = flags;
        this.stateMachine = stateMachine;
        this.views = views;
        this.storeService = storeService;
        this.cipher = cipher;
        this.publicIds = publicIds;
        this.events = events;
        this.auditService = auditService;
        this.notifications = notifications;
        this.accountGuard = accountGuard;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ============================================================================================ customer

    /** {@code AWAITING_PAYMENT -> PAYMENT_SUBMITTED}, unless the reference was used before (then flagged, 409). */
    public OrderResponse submit(Actor actor, String orderPublicId, PaymentRequest request) {
        accountGuard.requireCustomerCanBuy(actor);
        String reference = ReferenceNormalizer.normalize(request.referenceNumber());
        try {
            Optional<OrderResponse> accepted = transactions.execute(tx -> tryAccept(actor, orderPublicId, request,
                    reference));
            if (accepted != null && accepted.isPresent()) {
                return accepted.get();
            }
        } catch (DataIntegrityViolationException e) {
            // Another submission took the same (reference, bank) between our check and our insert
            log.info("Concurrent duplicate payment reference on order {}", orderPublicId);
        }
        transactions.executeWithoutResult(tx -> flag(actor, orderPublicId, request, reference));
        throw new ConflictException(ErrorCode.PAYMENT_REJECTED, GENERIC_REJECTION);
    }

    /** @return the updated order, or empty if the reference is a duplicate (nothing written) */
    private Optional<OrderResponse> tryAccept(Actor actor, String orderPublicId, PaymentRequest request,
                                              String reference) {
        Order order = customerOrder(actor, orderPublicId);
        if (order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) {
            throw new InvalidOrderStateException("This order is paid cash on delivery");
        }
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new InvalidOrderStateException("Order " + order.getPublicId() + " is " + order.getStatus()
                    + ": no payment is expected");
        }
        BankAccount account = storeAccount(order, request.storeBankAccountPublicId());
        if (submissions.findFirstByNormalizedReferenceAndDestinationBankCodeAndStatusIn(reference,
                account.bankCode(), SUCCESSFUL).isPresent()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        PaymentSubmission submission = new PaymentSubmission();
        submission.setId(UUID.randomUUID());
        submission.setPublicId(publicIds.generateUnique(PublicIdGenerator.PAYMENT_PREFIX,
                submissions::existsByPublicId));
        submission.setOrderId(order.getId());
        submission.setCustomerId(order.getCustomerId());
        submission.setStoreId(order.getStoreId());
        submission.setStoreBankAccountId(account.id());
        submission.setStoreBankAccountPublicId(account.publicId());
        submission.setDestinationBankCode(account.bankCode());
        submission.setReferenceNumber(TextSanitizer.cleanLine(request.referenceNumber()));
        submission.setNormalizedReference(reference);
        submission.setSourceBankCode(request.sourceBankCode());
        String accountNumber = request.sourceAccountNumber().replaceAll("[ -]", "");
        submission.setSourceAccountEncrypted(cipher.encrypt(accountNumber));
        submission.setSourceAccountLast4(AccountNumberCipher.lastFour(accountNumber));
        submission.setAttachmentKeys(request.attachmentKeys() == null ? List.of() : request.attachmentKeys());
        submission.setStatus(PaymentStatus.SUBMITTED);
        submission.setSubmittedAt(now);
        submissions.saveAndFlush(submission);
        stateMachine.apply(order, OrderAction.PAYMENT_SUBMIT, actor);
        return Optional.of(views.detail(order, Audience.CUSTOMER));
    }

    private void flag(Actor actor, String orderPublicId, PaymentRequest request, String reference) {
        Order order = customerOrder(actor, orderPublicId);
        BankAccount account = storeAccount(order, request.storeBankAccountPublicId());
        Optional<PaymentSubmission> original = submissions
                .findFirstByNormalizedReferenceAndDestinationBankCodeAndStatusIn(reference, account.bankCode(),
                        SUCCESSFUL);
        FlaggedPaymentReference flagged = new FlaggedPaymentReference();
        flagged.setId(UUID.randomUUID());
        flagged.setPublicId(publicIds.generateUnique(PublicIdGenerator.FLAGGED_REFERENCE_PREFIX,
                flags::existsByPublicId));
        flagged.setOrderId(order.getId());
        flagged.setOrderPublicId(order.getPublicId());
        flagged.setCustomerId(order.getCustomerId());
        flagged.setCustomerPublicId(order.getCustomerPublicId());
        flagged.setStoreId(order.getStoreId());
        flagged.setStorePublicId(order.getStorePublicId());
        flagged.setNormalizedReference(reference);
        flagged.setBankCode(account.bankCode());
        original.ifPresent(o -> {
            flagged.setOriginalSubmissionId(o.getId());
            orderRepository.findById(o.getOrderId())
                    .ifPresent(originalOrder -> flagged.setOriginalOrderPublicId(originalOrder.getPublicId()));
        });
        flagged.setStatus(FlagStatus.OPEN);
        flagged.setCreatedAt(clock.instant());
        flags.save(flagged);
        events.publish(order, OrderEventType.PaymentReferenceFlagged,
                Details.previous(order.getStatus().name()).flagged(flagged.getPublicId()));
        notifications.notifyAdmins("PAYMENT_REFERENCE_FLAGGED", flagged.getPublicId());
        log.info("Duplicate payment reference flagged as {} on order {}", flagged.getPublicId(), order.getPublicId());
    }

    // ============================================================================================ merchant

    @Transactional
    public OrderResponse verify(Actor actor, String orderPublicId) {
        accountGuard.requireMerchantCanAct(actor);
        Order order = storeOrder(actor, orderPublicId);
        PaymentSubmission submission = pending(order);
        stateMachine.apply(order, OrderAction.PAYMENT_VERIFY, actor);
        decide(submission, PaymentStatus.VERIFIED, actor, null);
        return views.detail(order, Audience.STORE);
    }

    /** Back to {@code AWAITING_PAYMENT}: the customer may resubmit until the original payment deadline. */
    @Transactional
    public OrderResponse reject(Actor actor, String orderPublicId, String reason) {
        accountGuard.requireMerchantCanAct(actor);
        Order order = storeOrder(actor, orderPublicId);
        PaymentSubmission submission = pending(order);
        String cleaned = TextSanitizer.cleanLine(reason);
        if (cleaned == null) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A reason is required");
        }
        order.setReason(cleaned);
        stateMachine.apply(order, OrderAction.PAYMENT_REJECT, actor);
        decide(submission, PaymentStatus.REJECTED, actor, cleaned);
        notifications.notifyCustomer(order.getCustomerId(), "PAYMENT_REJECTED", order.getPublicId());
        return views.detail(order, Audience.STORE);
    }

    // =============================================================================================== admin

    @Transactional(readOnly = true)
    public PageResponse<FlaggedReferenceResponse> flagged(FlagStatus status, Pageable pageable) {
        return PageResponse.from(status == null ? flags.findAll(pageable) : flags.findAllByStatus(status, pageable),
                FlaggedReferenceResponse::from);
    }

    @Transactional(readOnly = true)
    public FlaggedReferenceResponse flaggedOne(String publicId) {
        return FlaggedReferenceResponse.from(flags.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Flagged reference not found")));
    }

    @Transactional
    public FlaggedReferenceResponse review(Actor actor, String publicId, FlagReviewRequest request) {
        FlaggedPaymentReference flagged = flags.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Flagged reference not found"));
        if (flagged.getStatus() != FlagStatus.OPEN) {
            throw new ConflictException(ErrorCode.FLAG_ALREADY_REVIEWED, "Already " + flagged.getStatus());
        }
        String note = TextSanitizer.clean(request.note());
        if (note == null) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A note is required");
        }
        FlagStatus before = flagged.getStatus();
        flagged.setStatus(request.action() == PaymentDtos.FlagAction.ESCALATED ? FlagStatus.ESCALATED
                : FlagStatus.REVIEWED);
        flagged.setReviewNote(note);
        flagged.setReviewedBy(actor.publicId());
        flagged.setReviewedAt(clock.instant());
        auditService.record(actor, "FLAGGED_REFERENCE_" + flagged.getStatus(),
                AuditService.TARGET_FLAGGED_REFERENCE, publicId, Map.of("status", before.name()),
                Map.of("status", flagged.getStatus().name()), note);
        if (flagged.getStatus() == FlagStatus.ESCALATED) {
            notifications.notifyAdmins("PAYMENT_REFERENCE_ESCALATED", publicId);
        }
        return FlaggedReferenceResponse.from(flagged);
    }

    // ============================================================================================= helpers

    private Order customerOrder(Actor actor, String publicId) {
        return orderRepository.findByPublicIdAndCustomerId(publicId, actor.id())
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    private Order storeOrder(Actor actor, String publicId) {
        if (actor.storeId() == null) {
            throw new NotFoundException("Order not found");
        }
        return orderRepository.findByPublicIdAndStoreId(publicId, actor.storeId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    /** One of the store's active accounts (as listed by store-service right now). */
    private BankAccount storeAccount(Order order, String accountPublicId) {
        return storeService.bankAccounts(order.getStoreId()).stream()
                .filter(a -> a.publicId().equals(accountPublicId))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.BANK_ACCOUNT_NOT_AVAILABLE,
                        "That bank account is not one of the store's active accounts"));
    }

    private PaymentSubmission pending(Order order) {
        if (order.getStatus() != OrderStatus.PAYMENT_SUBMITTED) {
            throw new InvalidOrderStateException("Order " + order.getPublicId() + " is " + order.getStatus()
                    + ": there is no payment to verify");
        }
        return submissions.findFirstByOrderIdAndStatus(order.getId(), PaymentStatus.SUBMITTED)
                .orElseThrow(() -> new InvalidOrderStateException("No submitted payment found"));
    }

    private void decide(PaymentSubmission submission, PaymentStatus status, Actor actor, String reason) {
        submission.setStatus(status);
        submission.setDecidedAt(clock.instant());
        submission.setDecidedBy(actor.publicId());
        submission.setRejectionReason(reason);
        auditService.record(actor, "PAYMENT_" + status, AuditService.TARGET_PAYMENT, submission.getPublicId(),
                Map.of("status", PaymentStatus.SUBMITTED.name()),
                Map.of("status", status.name(), "sourceAccount",
                        AccountNumberCipher.mask(submission.getSourceAccountLast4())), reason);
    }
}
