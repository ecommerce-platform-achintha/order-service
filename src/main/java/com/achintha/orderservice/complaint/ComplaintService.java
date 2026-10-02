package com.achintha.orderservice.complaint;

import com.achintha.orderservice.audit.AuditService;
import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.PublicIdGenerator;
import com.achintha.orderservice.common.TextSanitizer;
import com.achintha.orderservice.complaint.ComplaintDtos.ComplaintResponse;
import com.achintha.orderservice.complaint.ComplaintDtos.CreateComplaintRequest;
import com.achintha.orderservice.complaint.ComplaintDtos.DecideRequest;
import com.achintha.orderservice.event.OrderEventPublisher;
import com.achintha.orderservice.event.OrderEventPublisher.Details;
import com.achintha.orderservice.event.OrderEventType;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.order.OrderStatus;
import com.achintha.orderservice.platform.PlatformSettings;
import com.achintha.orderservice.platform.SettingKeys;
import com.achintha.orderservice.ports.ImageStoragePort;
import com.achintha.orderservice.ports.NotificationPort;
import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Complaints (section 6.6): the customer files one from {@code READY_TO_SHIP} until {@code complaints.window-days}
 * after completion; the store may respond once; an admin decides. {@code UPHELD} is published as
 * {@code ComplaintDecided} with the {@code COMPLAINT_UPHELD} hint, which store-service turns into a merchant
 * penalty. No money is moved by the platform.
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private static final Set<OrderStatus> COMPLAINABLE = EnumSet.of(OrderStatus.READY_TO_SHIP, OrderStatus.SHIPPED,
            OrderStatus.COMPLETED);
    private static final Set<ComplaintStatus> ACTIVE = EnumSet.of(ComplaintStatus.OPEN, ComplaintStatus.UNDER_REVIEW);

    private final ComplaintRepository complaints;
    private final OrderRepository orders;
    private final PublicIdGenerator publicIds;
    private final PlatformSettings settings;
    private final OrderEventPublisher events;
    private final AuditService auditService;
    private final NotificationPort notifications;
    private final ImageStoragePort imageStorage;
    private final Clock clock;

    // ============================================================================================ customer

    @Transactional
    public ComplaintResponse create(Actor actor, CreateComplaintRequest request) {
        Order order = orders.findByPublicIdAndCustomerId(request.orderPublicId(), actor.id())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        Instant now = clock.instant();
        if (!COMPLAINABLE.contains(order.getStatus())) {
            throw new ConflictException(ErrorCode.COMPLAINT_NOT_ALLOWED,
                    "A complaint can be filed once the order is ready to ship");
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            int days = settings.intValue(SettingKeys.COMPLAINTS_WINDOW_DAYS);
            if (now.isAfter(order.getCompletedAt().plus(Duration.ofDays(days)))) {
                throw new ConflictException(ErrorCode.COMPLAINT_NOT_ALLOWED,
                        "Complaints are accepted up to " + days + " days after completion");
            }
        }
        if (complaints.existsByOrderIdAndStatusIn(order.getId(), ACTIVE)) {
            throw new ConflictException(ErrorCode.COMPLAINT_NOT_ALLOWED, "This order already has an open complaint");
        }
        Complaint complaint = new Complaint();
        complaint.setId(UUID.randomUUID());
        complaint.setPublicId(publicIds.generateUnique(PublicIdGenerator.COMPLAINT_PREFIX,
                complaints::existsByPublicId));
        complaint.setOrderId(order.getId());
        complaint.setCustomerId(order.getCustomerId());
        complaint.setStoreId(order.getStoreId());
        complaint.setType(request.type());
        complaint.setText(required(TextSanitizer.clean(request.text())));
        complaint.setAttachmentKeys(request.attachmentKeys() == null ? List.of() : request.attachmentKeys());
        complaint.setStatus(ComplaintStatus.OPEN);
        complaint.setCreatedAt(now);
        complaint.setUpdatedAt(now);
        complaints.save(complaint);
        notifications.notifyStore(order.getStoreId(), "COMPLAINT_FILED", complaint.getPublicId());
        notifications.notifyAdmins("COMPLAINT_FILED", complaint.getPublicId());
        return view(complaint, order);
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintResponse> customerList(Actor actor, Pageable pageable) {
        return page(complaints.findAllByCustomerId(actor.id(), pageable));
    }

    @Transactional(readOnly = true)
    public ComplaintResponse customerGet(Actor actor, String publicId) {
        return view(complaints.findByPublicIdAndCustomerId(publicId, actor.id())
                .orElseThrow(ComplaintService::notFound));
    }

    // ============================================================================================ merchant

    @Transactional(readOnly = true)
    public PageResponse<ComplaintResponse> storeList(Actor actor, Pageable pageable) {
        return page(complaints.findAllByStoreId(actor.storeId(), pageable));
    }

    @Transactional(readOnly = true)
    public ComplaintResponse storeGet(Actor actor, String publicId) {
        return view(storeOwned(actor, publicId));
    }

    /** The store's single response; the complaint then waits for an admin decision. */
    @Transactional
    public ComplaintResponse respond(Actor actor, String publicId, String response) {
        Complaint complaint = storeOwned(actor, publicId);
        if (complaint.getMerchantResponse() != null || complaint.getStatus() != ComplaintStatus.OPEN) {
            throw new ConflictException(ErrorCode.COMPLAINT_ALREADY_RESPONDED, "The store already responded");
        }
        Instant now = clock.instant();
        complaint.setMerchantResponse(required(TextSanitizer.clean(response)));
        complaint.setMerchantRespondedAt(now);
        complaint.setMerchantRespondedBy(actor.publicId());
        complaint.setStatus(ComplaintStatus.UNDER_REVIEW);
        complaint.setUpdatedAt(now);
        auditService.record(actor, "COMPLAINT_RESPONDED", AuditService.TARGET_COMPLAINT, publicId,
                Map.of("status", ComplaintStatus.OPEN.name()), Map.of("status", complaint.getStatus().name()), null);
        return view(complaint);
    }

    // =============================================================================================== admin

    @Transactional(readOnly = true)
    public PageResponse<ComplaintResponse> adminList(Collection<ComplaintStatus> statuses, Pageable pageable) {
        Collection<ComplaintStatus> filter = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(ComplaintStatus.class) : statuses;
        return page(complaints.findAllByStatusIn(filter, pageable));
    }

    @Transactional(readOnly = true)
    public ComplaintResponse adminGet(String publicId) {
        return view(complaints.findByPublicId(publicId).orElseThrow(ComplaintService::notFound));
    }

    @Transactional
    public ComplaintResponse decide(Actor actor, String publicId, DecideRequest request) {
        Complaint complaint = complaints.findByPublicId(publicId).orElseThrow(ComplaintService::notFound);
        if (!ACTIVE.contains(complaint.getStatus())) {
            throw new ConflictException(ErrorCode.COMPLAINT_ALREADY_DECIDED, "Already " + complaint.getStatus());
        }
        ComplaintStatus before = complaint.getStatus();
        Instant now = clock.instant();
        complaint.setStatus(request.decision() == ComplaintDtos.Decision.UPHELD ? ComplaintStatus.UPHELD
                : ComplaintStatus.DISMISSED);
        complaint.setAdminNotes(required(TextSanitizer.clean(request.notes())));
        complaint.setDecidedBy(actor.publicId());
        complaint.setDecidedAt(now);
        complaint.setUpdatedAt(now);
        Order order = orders.findById(complaint.getOrderId()).orElseThrow();
        Details details = Details.previous(order.getStatus().name())
                .complaint(complaint.getPublicId(), complaint.getStatus().name());
        if (complaint.getStatus() == ComplaintStatus.UPHELD) {
            details = details.merchantPenalty("COMPLAINT_UPHELD");
        }
        events.publish(order, OrderEventType.ComplaintDecided, details);
        auditService.record(actor, "COMPLAINT_" + complaint.getStatus(), AuditService.TARGET_COMPLAINT, publicId,
                Map.of("status", before.name()), Map.of("status", complaint.getStatus().name()),
                complaint.getAdminNotes().length() > 500 ? complaint.getAdminNotes().substring(0, 500)
                        : complaint.getAdminNotes());
        notifications.notifyCustomer(complaint.getCustomerId(), "COMPLAINT_" + complaint.getStatus(), publicId);
        notifications.notifyStore(complaint.getStoreId(), "COMPLAINT_" + complaint.getStatus(), publicId);
        return view(complaint, order);
    }

    // ============================================================================================= helpers

    private Complaint storeOwned(Actor actor, String publicId) {
        if (actor.storeId() == null) {
            throw notFound();
        }
        return complaints.findByPublicIdAndStoreId(publicId, actor.storeId()).orElseThrow(ComplaintService::notFound);
    }

    private PageResponse<ComplaintResponse> page(Page<Complaint> page) {
        return PageResponse.from(page, this::view);
    }

    private ComplaintResponse view(Complaint complaint) {
        return view(complaint, orders.findById(complaint.getOrderId()).orElseThrow());
    }

    private ComplaintResponse view(Complaint c, Order order) {
        return new ComplaintResponse(c.getPublicId(), order.getPublicId(), order.getStorePublicId(),
                order.getCustomerPublicId(), c.getType(), c.getText(),
                c.getAttachmentKeys().stream().map(imageStorage::urlFor).toList(), c.getStatus(),
                c.getMerchantResponse(), c.getMerchantRespondedAt(), c.getAdminNotes(), c.getDecidedAt(),
                c.getCreatedAt());
    }

    private static String required(String cleaned) {
        if (cleaned == null) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Text must not be empty");
        }
        return cleaned;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Complaint not found");
    }
}
