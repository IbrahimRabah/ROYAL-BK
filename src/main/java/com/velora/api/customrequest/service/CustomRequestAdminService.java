package com.velora.api.customrequest.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.Product;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.util.MoneyUtils;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.customrequest.domain.CustomOrderRequest;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.dto.CustomRequestAttachmentResponse;
import com.velora.api.customrequest.dto.CustomRequestFilter;
import com.velora.api.customrequest.dto.CustomRequestResponse;
import com.velora.api.customrequest.dto.CustomRequestSummaryResponse;
import com.velora.api.customrequest.repository.CustomOrderRequestRepository;
import com.velora.api.customrequest.repository.CustomRequestSpecifications;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom requests as staff work them: list, read, move through the statuses, quote.
 *
 * <p>Both changes — a status move and a quote — are commercial decisions ("who told the
 * customer 4,500?", "who rejected them?"), so each one is written to the audit log with
 * the acting staff member. The request row keeps only the latest note and the latest
 * quote; the audit log is the history.
 *
 * <p>Changes run under a row lock: the status and the quote decide what is allowed next,
 * so two staff acting at once must not both pass the same check.
 */
@Service
public class CustomRequestAdminService {

    private static final Logger log = LoggerFactory.getLogger(CustomRequestAdminService.class);
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final String ENTITY_TYPE = "CUSTOM_REQUEST";

    private final CustomOrderRequestRepository requestRepository;
    private final StorageService storageService;
    private final AuditService auditService;

    public CustomRequestAdminService(CustomOrderRequestRepository requestRepository,
                                     StorageService storageService,
                                     AuditService auditService) {
        this.requestRepository = requestRepository;
        this.storageService = storageService;
        this.auditService = auditService;
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PageResponse<CustomRequestSummaryResponse> list(CustomRequestFilter filter,
                                                           Pageable pageable) {
        OffsetDateTime from = filter.from() == null
                ? null : filter.from().atStartOfDay(CAIRO).toOffsetDateTime();
        // "to" is a day, inclusive: everything before the start of the next one.
        OffsetDateTime before = filter.to() == null
                ? null : filter.to().plusDays(1).atStartOfDay(CAIRO).toOffsetDateTime();

        Specification<CustomOrderRequest> spec = Specification
                .where(CustomRequestSpecifications.hasStatus(filter.status()))
                .and(CustomRequestSpecifications.hasType(filter.type()))
                .and(CustomRequestSpecifications.inGovernorate(filter.governorateId()))
                .and(CustomRequestSpecifications.createdFrom(from))
                .and(CustomRequestSpecifications.createdBefore(before))
                .and(CustomRequestSpecifications.matches(filter.q()));

        Page<CustomOrderRequest> page = requestRepository.findAll(spec, pageable);
        return PageResponse.from(page, this::toSummary);
    }

    @Transactional(readOnly = true)
    public CustomRequestResponse get(Long id) {
        return toResponse(load(id));
    }

    // ------------------------------------------------------------------ status

    /**
     * Moves a request to a status staff may set directly: CONTACTED, ACCEPTED or REJECTED.
     *
     * <ul>
     *   <li>QUOTED is reached by quoting, never set by hand — so a request can never be
     *       "quoted" with no quote.</li>
     *   <li>ACCEPTED needs a quote. From NEW or CONTACTED that is its own error, because
     *       "quote it first" is a different instruction from "that move is not allowed".</li>
     *   <li>CONVERTED is refused until converting a request into an order exists.</li>
     *   <li>ACCEPTED, REJECTED and CONVERTED cannot be left.</li>
     * </ul>
     */
    @Transactional
    public CustomRequestResponse changeStatus(Long id, CustomRequestStatus target, String note,
                                              Long actorId) {
        CustomOrderRequest request = lock(id);
        CustomRequestStatus current = request.getStatus();

        if (target == CustomRequestStatus.CONVERTED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A request cannot be marked CONVERTED yet: converting a request into an "
                            + "order is not available");
        }
        if (target == CustomRequestStatus.QUOTED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A request becomes QUOTED by giving a quote, not by setting the status");
        }
        if (target == CustomRequestStatus.ACCEPTED && !current.isTerminal()
                && request.getQuotedAmount() == null) {
            throw new BusinessException(ErrorCode.CUSTOM_REQUEST_QUOTE_REQUIRED);
        }
        if (!current.directTransitions().contains(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A request that is %s cannot become %s".formatted(current, target));
        }
        if (target == CustomRequestStatus.REJECTED && (note == null || note.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A note is required when rejecting a request");
        }

        applyStatus(request, target, note, actorId);
        return toResponse(requestRepository.save(request));
    }

    // ------------------------------------------------------------------- quote

    /**
     * Gives or changes the quote. From NEW or CONTACTED it also moves the request to
     * QUOTED — a request with a price on it that still says NEW would be a contradiction.
     * Allowed again while QUOTED (a revised price); not once the customer has accepted or
     * the request is closed.
     */
    @Transactional
    public CustomRequestResponse quote(Long id, BigDecimal amount, String note, Long actorId) {
        CustomOrderRequest request = lock(id);
        CustomRequestStatus current = request.getStatus();

        if (!current.canBeQuoted()) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A request that is %s can no longer be quoted".formatted(current));
        }

        BigDecimal previous = request.getQuotedAmount();
        BigDecimal rounded = MoneyUtils.round(amount);
        boolean amountChanged = previous == null || previous.compareTo(rounded) != 0;

        request.setQuotedAmount(rounded);
        if (note != null && !note.isBlank()) {
            request.setAdminNote(note.trim());
        }

        // Written before the status move, so the log reads in the order things happened:
        // the price was set, and that is what moved the request to QUOTED.
        if (amountChanged) {
            auditService.record(AuditAction.CUSTOM_REQUEST_QUOTED, ENTITY_TYPE, request.getId(),
                    request.getRequestNumber(),
                    previous == null ? null : MoneyUtils.round(previous), rounded,
                    note, actorId);
            log.info("Custom request {} quoted {} (was {})",
                    request.getRequestNumber(), rounded, previous);
        }

        if (current != CustomRequestStatus.QUOTED) {
            applyStatus(request, CustomRequestStatus.QUOTED, note, actorId);
        }
        return toResponse(requestRepository.save(request));
    }

    // ---------------------------------------------------------------- internal

    private void applyStatus(CustomOrderRequest request, CustomRequestStatus target,
                             String note, Long actorId) {
        CustomRequestStatus previous = request.getStatus();
        request.setStatus(target);
        if (note != null && !note.isBlank()) {
            request.setAdminNote(note.trim());
        }
        auditService.record(AuditAction.CUSTOM_REQUEST_STATUS_CHANGED, ENTITY_TYPE,
                request.getId(), request.getRequestNumber(),
                previous, target, note, actorId);
        log.info("Custom request {} moved {} -> {}", request.getRequestNumber(), previous, target);
    }

    private CustomOrderRequest load(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
    }

    private CustomOrderRequest lock(Long id) {
        return requestRepository.lockById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));
    }

    private CustomRequestSummaryResponse toSummary(CustomOrderRequest r) {
        return new CustomRequestSummaryResponse(
                r.getId(),
                r.getRequestNumber(),
                r.getType().name(),
                r.getStatus().name(),
                r.getContactName(),
                PhoneNormalizer.toLocalFormat(r.getPhone()),
                r.getGovernorate().getNameAr(),
                r.getQuantity(),
                r.getQuotedAmount(),
                r.getAttachments().size(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private CustomRequestResponse toResponse(CustomOrderRequest r) {
        Product product = r.getProduct();
        return new CustomRequestResponse(
                r.getId(),
                r.getRequestNumber(),
                r.getType().name(),
                r.getStatus().name(),
                product == null ? null : new CustomRequestResponse.ProductRef(
                        product.getId(), product.getSlug(), product.nameFor("ar"),
                        product.getFulfillmentType().name()),
                r.getCustomerId(),
                r.getContactName(),
                PhoneNormalizer.toLocalFormat(r.getPhone()),
                PhoneNormalizer.toLocalFormat(r.getAltPhone()),
                r.getEmail(),
                r.getGovernorate().getId(),
                r.getGovernorate().getNameAr(),
                r.getArea(),
                r.getStreetAddress(),
                r.getWidthCm(),
                r.getHeightCm(),
                r.getDepthCm(),
                r.getQuantity(),
                r.getNotes(),
                r.getAttachments().stream()
                        .map(a -> new CustomRequestAttachmentResponse(
                                a.getId(),
                                storageService.urlFor(a.getStorageKey()),
                                a.getContentType(),
                                a.getSizeBytes(),
                                a.getCreatedAt()))
                        .toList(),
                r.getQuotedAmount(),
                r.getAdminNote(),
                r.getConvertedOrderId(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
