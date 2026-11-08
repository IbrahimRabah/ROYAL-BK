package com.velora.api.marketing.web;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.marketing.domain.PromotionState;
import com.velora.api.marketing.dto.PromotionImpactResponse;
import com.velora.api.marketing.dto.PromotionResponse;
import com.velora.api.marketing.dto.PromotionSaveRequest;
import com.velora.api.marketing.service.PromotionService;
import com.velora.api.marketing.service.PromotionService.BannerKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Admin — Discounts", description = "Sale discounts and their banners. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/promotions")
public class AdminPromotionController {

    private final PromotionService service;

    public AdminPromotionController(PromotionService service) {
        this.service = service;
    }

    @Operation(summary = "Discounts, newest first",
            description = "`state` filters: DRAFT, PAUSED, SCHEDULED, RUNNING or ENDED.")
    @GetMapping
    public PageResponse<PromotionResponse> list(
            @RequestParam(required = false) PromotionState state,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(state, pageable);
    }

    @Operation(summary = "One discount")
    @GetMapping("/{id}")
    public PromotionResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Create a discount", description = "Saved as DRAFT: it changes no price until it is activated.")
    @PostMapping
    public ResponseEntity<PromotionResponse> create(@Valid @RequestBody PromotionSaveRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, principal.id()));
    }

    @Operation(summary = "Replace a discount",
            description = "An active discount is re-checked against cost prices, so an edit cannot do what activation refuses.")
    @PutMapping("/{id}")
    public PromotionResponse update(@PathVariable Long id, @Valid @RequestBody PromotionSaveRequest request,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        return service.update(id, request, principal.id());
    }

    @Operation(summary = "What would this discount do?",
            description = "Affected products and variants, example prices, variants that would fall below cost "
                    + "(activation is refused while any), and overlapping discounts. Changes nothing.")
    @GetMapping("/{id}/impact")
    public PromotionImpactResponse impact(@PathVariable Long id) {
        return service.impact(id);
    }

    @Operation(summary = "Switch on (or resume)",
            description = "409 PROMOTION_BELOW_COST when a variant would sell below cost; 409 PROMOTION_ALREADY_ENDED "
                    + "when its end date has passed. From its start date the price changes by itself.")
    @PostMapping("/{id}/activate")
    public PromotionResponse activate(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return service.activate(id, principal.id());
    }

    @Operation(summary = "Pause", description = "Prices go back to normal at once. Resume with activate.")
    @PostMapping("/{id}/pause")
    public PromotionResponse pause(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return service.pause(id, principal.id());
    }

    @Operation(summary = "End now", description = "A running discount stops at this moment; one that has not started is paused.")
    @PostMapping("/{id}/end")
    public PromotionResponse end(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return service.end(id, principal.id());
    }

    @Operation(summary = "Delete", description = "409 PROMOTION_IN_USE when an order or an announcement uses it: end it instead.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        service.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Upload the banner image",
            description = "JPEG, PNG, WebP or AVIF, up to 5 MB, checked by its bytes. `kind`: DESKTOP (about 1600x600) "
                    + "or MOBILE (about 800x800). Replaces the one there.")
    @PostMapping(value = "/{id}/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PromotionResponse uploadBanner(@PathVariable Long id,
                                          @RequestParam(defaultValue = "DESKTOP") BannerKind kind,
                                          @RequestPart("file") MultipartFile file,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return service.setBanner(id, kind, file, principal.id());
    }

    @Operation(summary = "Remove a banner image")
    @DeleteMapping("/{id}/banner")
    public PromotionResponse removeBanner(@PathVariable Long id,
                                          @RequestParam(defaultValue = "DESKTOP") BannerKind kind,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return service.removeBanner(id, kind, principal.id());
    }
}
