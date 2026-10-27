package com.velora.api.store.web;

import com.velora.api.store.dto.StoreProfileRequest;
import com.velora.api.store.dto.StoreProfileResponse;
import com.velora.api.store.service.StoreProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Store profile",
        description = "The seller details printed on invoices. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/settings/store-profile")
public class AdminStoreProfileController {

    private final StoreProfileService storeProfileService;

    public AdminStoreProfileController(StoreProfileService storeProfileService) {
        this.storeProfileService = storeProfileService;
    }

    @Operation(summary = "Current seller details",
            description = "`missingFields` lists what is still empty (address, phone, email). "
                    + "Empty fields are omitted from the invoice rather than printed blank. "
                    + "The tax number and commercial register are not listed: the invoice "
                    + "does not print them.")
    @GetMapping
    public StoreProfileResponse get() {
        return storeProfileService.get();
    }

    @Operation(summary = "Update the seller details",
            description = """
                    Takes effect on invoices issued from now on. Existing invoices keep
                    the details that were printed on them — an invoice must always show
                    what it showed when it was issued.

                    Leave `taxNumber` empty until registered.
                    """)
    @PutMapping
    public StoreProfileResponse update(@Valid @RequestBody StoreProfileRequest request) {
        return storeProfileService.update(request);
    }
}
