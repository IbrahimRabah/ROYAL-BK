package com.velora.api.store.service;

import com.velora.api.common.config.BrandingProperties;
import com.velora.api.store.domain.StoreProfile;
import com.velora.api.store.dto.StoreProfileRequest;
import com.velora.api.store.dto.StoreProfileResponse;
import com.velora.api.store.repository.StoreProfileRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seller's legal identity — one row, read on every invoice.
 */
@Service
public class StoreProfileService {

    private static final Logger log = LoggerFactory.getLogger(StoreProfileService.class);
    private static final int PROFILE_ID = 1;

    private final StoreProfileRepository repository;
    private final BrandingProperties branding;

    public StoreProfileService(StoreProfileRepository repository, BrandingProperties branding) {
        this.repository = repository;
        this.branding = branding;
    }

    /**
     * The profile, creating a minimal one if the seed row is missing. Never returns
     * null: an invoice cannot be issued without a seller name.
     */
    @Transactional
    public StoreProfile require() {
        return repository.findById(PROFILE_ID).orElseGet(() -> {
            log.warn("No store profile found. Creating a placeholder — set the real "
                    + "details at PUT /api/v1/admin/settings/store-profile");
            StoreProfile profile = new StoreProfile();
            profile.setId(PROFILE_ID);
            profile.setLegalName(branding.getBrandName());
            profile.setInvoiceFooterNote("شكراً لتسوقكم من " + branding.getBrandName());
            return repository.save(profile);
        });
    }

    @Transactional(readOnly = true)
    public StoreProfileResponse get() {
        StoreProfile profile = repository.findById(PROFILE_ID).orElseGet(StoreProfile::new);
        return toResponse(profile);
    }

    @Transactional
    public StoreProfileResponse update(StoreProfileRequest request) {
        StoreProfile profile = require();

        profile.setLegalName(request.legalName());
        profile.setLegalNameEn(blankToNull(request.legalNameEn()));
        profile.setAddress(blankToNull(request.address()));
        profile.setPhone(blankToNull(request.phone()));
        profile.setEmail(blankToNull(request.email()));
        profile.setTaxNumber(blankToNull(request.taxNumber()));
        profile.setCommercialRegister(blankToNull(request.commercialRegister()));
        profile.setWebsite(blankToNull(request.website()));
        profile.setInvoiceFooterNote(blankToNull(request.invoiceFooterNote()));
        profile.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        StoreProfile saved = repository.save(profile);
        log.info("Store profile updated. Tax number {}",
                saved.hasTaxNumber() ? "set" : "still empty");

        return toResponse(saved);
    }

    // ------------------------------------------------------------------ internal

    /**
     * Blank becomes null so the invoice template can simply test for presence. An
     * empty string would print an empty labelled row.
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private StoreProfileResponse toResponse(StoreProfile profile) {
        List<String> missing = new ArrayList<>();
        if (profile.getAddress() == null) {
            missing.add("address");
        }
        if (profile.getPhone() == null) {
            missing.add("phone");
        }
        if (profile.getEmail() == null) {
            missing.add("email");
        }
        if (!profile.hasTaxNumber()) {
            missing.add("taxNumber");
        }
        if (!profile.hasCommercialRegister()) {
            missing.add("commercialRegister");
        }

        return new StoreProfileResponse(
                profile.getLegalName(),
                profile.getLegalNameEn(),
                profile.getAddress(),
                profile.getPhone(),
                profile.getEmail(),
                profile.getTaxNumber(),
                profile.getCommercialRegister(),
                profile.getWebsite(),
                profile.getInvoiceFooterNote(),
                missing);
    }
}
