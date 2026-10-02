package com.velora.api.customrequest.service;

import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.common.ratelimit.RateLimiter;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.storage.StoredFile;
import com.velora.api.common.util.PhoneNormalizer;
import com.velora.api.customrequest.domain.CustomOrderRequest;
import com.velora.api.customrequest.domain.CustomOrderRequestSequence;
import com.velora.api.customrequest.domain.CustomRequestAttachment;
import com.velora.api.customrequest.domain.CustomRequestStatus;
import com.velora.api.customrequest.domain.CustomRequestType;
import com.velora.api.customrequest.dto.CustomRequestAttachmentResponse;
import com.velora.api.customrequest.dto.CustomRequestCreateRequest;
import com.velora.api.customrequest.dto.CustomRequestCreatedResponse;
import com.velora.api.customrequest.repository.CustomOrderRequestRepository;
import com.velora.api.customrequest.repository.CustomOrderRequestSequenceRepository;
import com.velora.api.shipping.domain.Governorate;
import com.velora.api.shipping.repository.GovernorateRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Custom requests as the customer sees them: creating one and attaching reference
 * images. Both endpoints are public, so both are rate limited per client IP.
 *
 * <p>A request is not a sale. Nothing here reserves stock — not even for a size request
 * on a ready-made product — and nothing is priced until staff quote it.
 *
 * <p>The request number has no gaps. It is allocated under a row lock in the same
 * transaction that writes the request, exactly as invoice numbers are, so a rollback
 * returns the number. The customer is given it over the phone.
 */
@Service
public class CustomRequestService {

    private static final Logger log = LoggerFactory.getLogger(CustomRequestService.class);

    /** The business week and fiscal year follow Cairo, not UTC. */
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    static final int MAX_ATTACHMENTS = 5;
    private static final String STORAGE_FOLDER = "custom-requests";

    private final CustomOrderRequestRepository requestRepository;
    private final CustomOrderRequestSequenceRepository sequenceRepository;
    private final GovernorateRepository governorateRepository;
    private final ProductRepository productRepository;
    private final StorageService storageService;
    private final RateLimiter rateLimiter;
    private final CustomRequestTokenService tokenService;

    private final int createMaxPerIp;
    private final Duration createWindow;
    private final int uploadMaxPerIp;
    private final Duration uploadWindow;

    public CustomRequestService(
            CustomOrderRequestRepository requestRepository,
            CustomOrderRequestSequenceRepository sequenceRepository,
            GovernorateRepository governorateRepository,
            ProductRepository productRepository,
            StorageService storageService,
            RateLimiter rateLimiter,
            CustomRequestTokenService tokenService,
            @Value("${velora.rate-limit.custom-request.max-per-ip:10}") int createMaxPerIp,
            @Value("${velora.rate-limit.custom-request.window-minutes:60}") long createWindowMinutes,
            @Value("${velora.rate-limit.custom-request-upload.max-per-ip:30}") int uploadMaxPerIp,
            @Value("${velora.rate-limit.custom-request-upload.window-minutes:60}")
            long uploadWindowMinutes) {
        this.requestRepository = requestRepository;
        this.sequenceRepository = sequenceRepository;
        this.governorateRepository = governorateRepository;
        this.productRepository = productRepository;
        this.storageService = storageService;
        this.rateLimiter = rateLimiter;
        this.tokenService = tokenService;
        this.createMaxPerIp = createMaxPerIp;
        this.createWindow = Duration.ofMinutes(createWindowMinutes);
        this.uploadMaxPerIp = uploadMaxPerIp;
        this.uploadWindow = Duration.ofMinutes(uploadWindowMinutes);
    }

    // ------------------------------------------------------------------- create

    /**
     * @param customerId the signed-in customer, or null for a guest
     * @param clientIp   what the rate limit is keyed on
     */
    @Transactional
    public CustomRequestCreatedResponse create(CustomRequestCreateRequest in, Long customerId,
                                               String clientIp) {
        // Before anything else, so a flood of invalid requests is throttled too.
        if (!rateLimiter.tryAcquire("custom-request:ip:" + clientIp, createMaxPerIp, createWindow)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        String phone = PhoneNormalizer.toE164(in.phone());
        if (phone == null) {
            throw new BusinessException(ErrorCode.INVALID_PHONE_FORMAT);
        }
        String altPhone = null;
        if (in.altPhone() != null && !in.altPhone().isBlank()) {
            altPhone = PhoneNormalizer.toE164(in.altPhone());
            if (altPhone == null) {
                throw new BusinessException(ErrorCode.INVALID_PHONE_FORMAT,
                        "The alternate phone number is not a valid Egyptian mobile");
            }
        }

        Governorate governorate = governorateRepository.findById(in.governorateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Governorate not found"));

        Product product = resolveProduct(in);

        CustomOrderRequest request = new CustomOrderRequest();
        request.setType(in.type());
        request.setStatus(CustomRequestStatus.NEW);
        request.setProduct(product);
        request.setCustomerId(customerId);
        request.setContactName(in.contactName().trim());
        request.setPhone(phone);
        request.setAltPhone(altPhone);
        request.setEmail(blankToNull(in.email() == null ? null : in.email().toLowerCase(Locale.ROOT)));
        request.setGovernorate(governorate);
        request.setArea(blankToNull(in.area()));
        request.setStreetAddress(blankToNull(in.streetAddress()));
        request.setWidthCm(in.widthCm());
        request.setHeightCm(in.heightCm());
        request.setDepthCm(in.depthCm());
        request.setQuantity(in.quantity() == null ? 1 : in.quantity());
        request.setNotes(blankToNull(in.notes()));

        int year = LocalDate.now(CAIRO).getYear();
        int sequence = allocateNumber(year);
        request.setFiscalYear(year);
        request.setSequenceNumber(sequence);
        request.setRequestNumber("REQ-%d-%06d".formatted(year, sequence));

        CustomOrderRequest saved = requestRepository.save(request);

        // Never log the number in full.
        log.info("Custom request {} created ({}, {}, phone {})",
                saved.getRequestNumber(), saved.getType(), customerId == null ? "guest" : "account",
                PhoneNormalizer.mask(phone));

        CustomRequestTokenService.Token token = tokenService.issue(saved.getId(), Instant.now());
        return new CustomRequestCreatedResponse(
                saved.getId(),
                saved.getRequestNumber(),
                saved.getStatus().name(),
                token.value(),
                OffsetDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC),
                saved.getCreatedAt());
    }

    /**
     * What a request may be about. A public caller must not learn which products exist
     * but are not on sale, so a draft or archived product is "not found", the same as an
     * id that does not exist.
     */
    private Product resolveProduct(CustomRequestCreateRequest in) {
        if (in.type() == CustomRequestType.SIZE_VARIANT) {
            if (in.productId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "productId is required for a size request");
            }
            if (in.widthCm() == null && in.heightCm() == null && in.depthCm() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Give at least one dimension for a size request");
            }
        }
        if (in.productId() == null) {
            return null;
        }

        Product product = productRepository.findByIdAndArchivedAtIsNull(in.productId())
                .filter(p -> p.getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        if (in.type() == CustomRequestType.SIZE_VARIANT && !product.isReadyMade()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A size request must be for a ready-made product");
        }
        return product;
    }

    /**
     * Takes the next number for the year, under a row lock. Concurrent creators queue
     * here instead of reading the same value; the lock is released with the transaction.
     */
    private int allocateNumber(int year) {
        // Guarantees the row exists even for two requests arriving first thing in a new
        // year; see the repository for why this is not left to a plain insert.
        sequenceRepository.ensureYear(year);

        CustomOrderRequestSequence sequence = sequenceRepository.lockForYear(year)
                .orElseThrow(() -> new IllegalStateException(
                        "Sequence row for " + year + " disappeared after being ensured"));
        int next = sequence.next();
        sequenceRepository.save(sequence);
        return next;
    }

    // -------------------------------------------------------------- attachments

    /**
     * Attaches one reference image. Allowed only for the caller who created the request:
     * either they hold the token returned at creation, or they are the signed-in customer
     * the request belongs to. Anything else is a 404 — "not yours" must look exactly like
     * "does not exist".
     *
     * @param token        the {@code X-Request-Token} value, or null
     * @param callerUserId the signed-in caller, or null
     */
    @Transactional
    public CustomRequestAttachmentResponse addAttachment(Long requestId, String token,
                                                         Long callerUserId, MultipartFile file,
                                                         String clientIp) {
        if (!rateLimiter.tryAcquire("custom-request-upload:ip:" + clientIp,
                uploadMaxPerIp, uploadWindow)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        // Locked, so two uploads racing for the last slot cannot both see room for it.
        CustomOrderRequest request = requestRepository.lockById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOM_REQUEST_NOT_FOUND));

        boolean holdsToken = tokenService.isValid(token, requestId, Instant.now());
        boolean isOwner = callerUserId != null && callerUserId.equals(request.getCustomerId());
        if (!holdsToken && !isOwner) {
            throw new BusinessException(ErrorCode.CUSTOM_REQUEST_NOT_FOUND);
        }

        if (request.getStatus() != CustomRequestStatus.NEW) {
            throw new BusinessException(ErrorCode.CUSTOM_REQUEST_CLOSED);
        }
        if (request.getAttachments().size() >= MAX_ATTACHMENTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A request can have at most %d images".formatted(MAX_ATTACHMENTS));
        }

        // Size, declared type AND the file's real bytes are checked in here.
        StoredFile stored = storageService.store(file, STORAGE_FOLDER);
        try {
            CustomRequestAttachment attachment = new CustomRequestAttachment();
            attachment.setStorageKey(stored.key());
            attachment.setContentType(stored.contentType());
            attachment.setSizeBytes(stored.sizeBytes());
            request.addAttachment(attachment);
            // flush(), not saveAndFlush(): the request is managed, so save() would merge it
            // and cascade the merge to a COPY of the attachment, leaving this instance
            // without an id. Flushing persists this very instance.
            requestRepository.flush();

            return new CustomRequestAttachmentResponse(
                    attachment.getId(),
                    storageService.urlFor(attachment.getStorageKey()),
                    attachment.getContentType(),
                    attachment.getSizeBytes(),
                    attachment.getCreatedAt());
        } catch (RuntimeException ex) {
            // The file is on disk but the row never made it — do not leave it orphaned.
            storageService.delete(stored.key());
            throw ex;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
