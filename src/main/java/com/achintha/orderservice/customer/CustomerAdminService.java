package com.achintha.orderservice.customer;

import com.achintha.orderservice.audit.AuditService;
import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.common.TextSanitizer;
import com.achintha.orderservice.customer.CodPrivilegeService.CodView;
import com.achintha.orderservice.customer.CustomerDtos.CustomerScoreResponse;
import com.achintha.orderservice.customer.CustomerDtos.ObjectionDecisionRequest;
import com.achintha.orderservice.customer.CustomerDtos.ObjectionResponse;
import com.achintha.orderservice.customer.CustomerDtos.ScoreEntry;
import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ConflictException;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.order.Order;
import com.achintha.orderservice.order.OrderRepository;
import com.achintha.orderservice.ports.NotificationPort;
import com.achintha.orderservice.security.Actor;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin side of customer standing: score view and COD objection decisions (sections 6.5, 13.1). */
@Service
@RequiredArgsConstructor
public class CustomerAdminService {

    private final CodObjectionRepository objections;
    private final OrderRepository orders;
    private final CustomerScoreEventRepository scoreEvents;
    private final CodPrivilegeService codService;
    private final AuditService auditService;
    private final NotificationPort notifications;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustomerScoreResponse score(String customerPublicId) {
        UUID customerId = orders.findFirstByCustomerPublicId(customerPublicId).map(Order::getCustomerId)
                .orElseThrow(() -> new NotFoundException("No orders for this customer"));
        CodView cod = codService.view(customerId);
        var recent = scoreEvents.findAllByCustomerIdOrderByIdDesc(customerId, PageRequest.of(0, 20)).stream()
                .map(e -> new ScoreEntry(e.getEventType(), e.getDelta(), e.getCreatedAt())).toList();
        return new CustomerScoreResponse(customerPublicId, scoreEvents.scoreOf(customerId), cod.totalRefusals(),
                cod.refusalsTowardSuspension(), cod.suspended(), cod.suspendedUntil(), recent);
    }

    @Transactional(readOnly = true)
    public PageResponse<ObjectionResponse> objections(ObjectionStatus status, Pageable pageable) {
        return PageResponse.from(status == null ? objections.findAll(pageable)
                : objections.findAllByStatus(status, pageable), this::view);
    }

    /** {@code UPHELD} reverses the refusal of that order and lifts the suspension it caused. */
    @Transactional
    public ObjectionResponse decide(Actor actor, String orderPublicId, ObjectionDecisionRequest request) {
        Order order = orders.findByPublicId(orderPublicId).orElseThrow(() -> new NotFoundException("Order not found"));
        CodObjection objection = objections.findByOrderId(order.getId())
                .orElseThrow(() -> new NotFoundException("No objection for this order"));
        if (objection.getStatus() != ObjectionStatus.OPEN) {
            throw new ConflictException(ErrorCode.OBJECTION_ALREADY_DECIDED, "Already " + objection.getStatus());
        }
        String note = TextSanitizer.clean(request.note());
        if (note == null) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A note is required");
        }
        objection.setStatus(request.decision() == CustomerDtos.ObjectionDecision.UPHELD ? ObjectionStatus.UPHELD
                : ObjectionStatus.DISMISSED);
        objection.setAdminNote(note);
        objection.setDecidedBy(actor.publicId());
        objection.setDecidedAt(clock.instant());
        if (objection.getStatus() == ObjectionStatus.UPHELD) {
            codService.reverseRefusal(order.getCustomerId(), order.getCustomerPublicId(), order.getId());
        }
        auditService.record(actor, "COD_OBJECTION_" + objection.getStatus(), AuditService.TARGET_COD_OBJECTION,
                orderPublicId, Map.of("status", ObjectionStatus.OPEN.name()),
                Map.of("status", objection.getStatus().name()), note.length() > 500 ? note.substring(0, 500) : note);
        notifications.notifyCustomer(order.getCustomerId(), "COD_OBJECTION_" + objection.getStatus(), orderPublicId);
        return view(objection, order);
    }

    private ObjectionResponse view(CodObjection objection) {
        return view(objection, orders.findById(objection.getOrderId()).orElseThrow());
    }

    private static ObjectionResponse view(CodObjection o, Order order) {
        return new ObjectionResponse(order.getPublicId(), order.getCustomerPublicId(), order.getStorePublicId(),
                o.getText(), o.getStatus(), o.getAdminNote(), o.getDecidedAt(), o.getCreatedAt());
    }
}
