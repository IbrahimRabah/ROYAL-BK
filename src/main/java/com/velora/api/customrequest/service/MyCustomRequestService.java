package com.velora.api.customrequest.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.Product;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.customrequest.domain.CustomOrderRequest;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.dto.MyCustomRequestCountResponse;
import com.velora.api.customrequest.dto.MyCustomRequestResponse;
import com.velora.api.customrequest.dto.MyCustomRequestSummaryResponse;
import com.velora.api.customrequest.dto.MyCustomRequestSummaryResponse.ProductRef;
import com.velora.api.customrequest.repository.CustomOrderRequestRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A signed-in customer's own custom requests: read them, see the quote, accept or reject it.
 *
 * <p><b>Ownership is {@code customer_id} and nothing else.</b> A request is never matched to an
 * account by phone number or email - anyone can register with a stranger's number, and that would
 * let them read the stranger's request. A guest's request (customer_id NULL) never appears for any
 * account and stays phone-only. A request that is not the caller's is "not found", the same answer
 * as one that does not exist.
 *
 * <p><b>What the customer never sees:</b> the staff note ({@code admin_note}). That one box holds
 * both pricing notes and the mandatory reason for a rejection, so it will sometimes contain what a
 * customer must not read. A customer-visible message needs its own, explicitly labelled field.
 *
 * <p><b>The two things the customer may do</b> are QUOTED -> ACCEPTED and QUOTED -> REJECTED. The
 * server says whether they are available ({@code canAccept} / {@code canReject}); the client never
 * works it out from the status.
 *
 * <p><b>Races.</b> Both actions take the same row lock as the admin paths, so the first writer wins
 * and the status is re-read under the lock. Accepting also carries the amount the customer saw:
 * staff may revise a quote while it is QUOTED, the status stays QUOTED, and no lock could tell the
 * customer's accept apart from the price they never saw. A repeat that asks for what already
 * happened (accept an accepted request at the same price, reject a rejected one) is a success, not a
 * conflict; only opposite outcomes conflict.
 */
@Service
public class MyCustomRequestService {

    private static final Logger log = LoggerFactory.getLogger(MyCustomRequestService.class);

    private static final String ENTITY_TYPE = "CUSTOM_REQUEST";
    private static final int MAX_PAGE_SIZE = 50;
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final CustomOrderRequestRepository requestRepository;
    private final AuditService auditService;

    public MyCustomRequestService(CustomOrderRequestRepository requestRepository,
                                  AuditService auditService) {
        this.requestRepository = requestRepository;
        this.auditService = auditService;
    }

    // -------------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PageResponse<MyCustomRequestSummaryResponse> list(Long customerId, Pageable pageable,
                                                             String locale) {
        // Fixed order: newest first. The client cannot re-sort.
        Pageable page = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), NEWEST_FIRST);
        return PageResponse.from(requestRepository.findByCustomerId(customerId, page),
                r -> toSummary(r, locale));
    }

    @Transactional(readOnly = true)
    public MyCustomRequestResponse get(Long customerId, String requestNumber, String locale) {
        return toResponse(requestRepository
                .findByRequestNumberAndCustomerId(requestNumber, customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOM_REQUEST_NOT_FOUND)),
                locale);
    }

    /** For a badge: how many of their requests are waiting for their answer. */
    @Transactional(readOnly = true)
    public MyCustomRequestCountResponse awaitingResponse(Long customerId) {
        return new MyCustomRequestCountResponse(
                requestRepository.countByCustomerIdAndStatus(customerId, CustomRequestStatus.QUOTED));
    }

    // ------------------------------------------------------------------------- actions

    @Transactional
    public MyCustomRequestResponse accept(Long customerId, String requestNumber, BigDecimal seenAmount,
                                          String locale) {
        CustomOrderRequest request = lock(customerId, requestNumber);
        CustomRequestStatus current = request.getStatus();
        BigDecimal seen = MoneyUtils.round(seenAmount);

        if (current == CustomRequestStatus.NEW || current == CustomRequestStatus.CONTACTED) {
            throw new BusinessException(ErrorCode.CUSTOM_REQUEST_QUOTE_REQUIRED);
        }
        if (current == CustomRequestStatus.REJECTED) {
            throw new BusinessException(ErrorCode.QUOTE_ALREADY_ANSWERED,
                    "This request was rejected, so its quote cannot be accepted");
        }
        // QUOTED, or already on the accepted path (by this customer, or by staff on the phone).
        if (request.getQuotedAmount() == null || request.getQuotedAmount().compareTo(seen) != 0) {
            throw new BusinessException(ErrorCode.QUOTE_CHANGED);
        }
        if (current != CustomRequestStatus.QUOTED) {
            return toResponse(request, locale);   // same outcome already: success, nothing to do
        }

        request.setStatus(CustomRequestStatus.ACCEPTED);
        MyCustomRequestResponse response = toResponse(requestRepository.save(request), locale);
        auditService.record(AuditAction.CUSTOM_REQUEST_ACCEPTED_BY_CUSTOMER, ENTITY_TYPE,
                request.getId(), request.getRequestNumber(),
                CustomRequestStatus.QUOTED, CustomRequestStatus.ACCEPTED,
                "Accepted at " + seen.toPlainString(), customerId);
        log.info("Custom request {} accepted by its customer at {}", request.getRequestNumber(), seen);
        return response;
    }

    @Transactional
    public MyCustomRequestResponse reject(Long customerId, String requestNumber, String locale) {
        CustomOrderRequest request = lock(customerId, requestNumber);
        CustomRequestStatus current = request.getStatus();

        if (current == CustomRequestStatus.NEW || current == CustomRequestStatus.CONTACTED) {
            throw new BusinessException(ErrorCode.CUSTOM_REQUEST_QUOTE_REQUIRED);
        }
        if (current == CustomRequestStatus.REJECTED) {
            return toResponse(request, locale);   // same outcome already: success, nothing to do
        }
        if (current != CustomRequestStatus.QUOTED) {
            throw new BusinessException(ErrorCode.QUOTE_ALREADY_ANSWERED,
                    "This request was already accepted, so its quote cannot be rejected");
        }

        request.setStatus(CustomRequestStatus.REJECTED);
        MyCustomRequestResponse response = toResponse(requestRepository.save(request), locale);
        auditService.record(AuditAction.CUSTOM_REQUEST_REJECTED_BY_CUSTOMER, ENTITY_TYPE,
                request.getId(), request.getRequestNumber(),
                CustomRequestStatus.QUOTED, CustomRequestStatus.REJECTED, null, customerId);
        log.info("Custom request {} rejected by its customer", request.getRequestNumber());
        return response;
    }

    // ------------------------------------------------------------------------- internal

    private CustomOrderRequest lock(Long customerId, String requestNumber) {
        return requestRepository.lockByRequestNumberAndCustomerId(requestNumber, customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
    }

    /** Decided here, by the server: only a quote that is waiting for an answer can be answered. */
    private static boolean awaitingAnswer(CustomOrderRequest r) {
        return r.getStatus() == CustomRequestStatus.QUOTED && r.getQuotedAmount() != null;
    }

    private static ProductRef productRef(CustomOrderRequest r, String locale) {
        Product product = r.getProduct();
        return product == null ? null : new ProductRef(product.getSlug(), product.nameFor(locale));
    }

    private MyCustomRequestSummaryResponse toSummary(CustomOrderRequest r, String locale) {
        return new MyCustomRequestSummaryResponse(
                r.getRequestNumber(), r.getType().name(), r.getStatus().name(), r.getCreatedAt(),
                productRef(r, locale), r.getQuotedAmount(), r.getQuotedAt(),
                awaitingAnswer(r), awaitingAnswer(r));
    }

    private MyCustomRequestResponse toResponse(CustomOrderRequest r, String locale) {
        return new MyCustomRequestResponse(
                r.getRequestNumber(), r.getType().name(), r.getStatus().name(), r.getCreatedAt(),
                productRef(r, locale), r.getNotes(), r.getQuantity(),
                r.getWidthCm(), r.getHeightCm(), r.getDepthCm(),
                r.getQuotedAmount(), r.getQuotedAt(), awaitingAnswer(r), awaitingAnswer(r));
    }
}
