package com.velora.api.seo.web;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.seo.domain.FaqScope;
import com.velora.api.seo.domain.FaqStatus;
import com.velora.api.seo.domain.FaqTopic;
import com.velora.api.seo.dto.AdminFaqResponse;
import com.velora.api.seo.dto.FaqBulkStatusRequest;
import com.velora.api.seo.dto.FaqReorderRequest;
import com.velora.api.seo.dto.FaqSaveRequest;
import com.velora.api.seo.service.FaqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — FAQs", description = "Write and publish FAQs. ROLE_ADMIN or ROLE_SEO_EDITOR.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/faqs")
public class AdminFaqController {

    private final FaqService faqService;

    public AdminFaqController(FaqService faqService) {
        this.faqService = faqService;
    }

    @Operation(summary = "FAQs of every state, newest first")
    @GetMapping
    public PageResponse<AdminFaqResponse> list(
            @RequestParam(required = false) FaqScope scope,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) FaqStatus status,
            @RequestParam(required = false) FaqTopic topic,
            @RequestParam(required = false, name = "q") String query,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return faqService.list(scope, targetId, status, topic, query, pageable);
    }

    @Operation(summary = "Create a FAQ", description = "PRODUCT and CATEGORY need targetId; GENERAL takes a topic.")
    @PostMapping
    public ResponseEntity<AdminFaqResponse> create(@Valid @RequestBody FaqSaveRequest request,
                                                   @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(faqService.create(request, principal.id()));
    }

    @Operation(summary = "Replace a FAQ")
    @PutMapping("/{id}")
    public AdminFaqResponse update(@PathVariable Long id, @Valid @RequestBody FaqSaveRequest request,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return faqService.update(id, request, principal.id());
    }

    @Operation(summary = "Delete a FAQ")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        faqService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Put FAQs in order", description = "The ids in the order they should appear.")
    @PutMapping("/reorder")
    public Map<String, Integer> reorder(@Valid @RequestBody FaqReorderRequest request,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return Map.of("reordered", faqService.reorder(request.ids(), principal.id()));
    }

    @Operation(summary = "Publish or unpublish many", description = "At most 500 ids. Returns how many changed.")
    @PostMapping("/bulk-status")
    public Map<String, Integer> bulkStatus(@Valid @RequestBody FaqBulkStatusRequest request,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return Map.of("changed", faqService.bulkStatus(request, principal.id()));
    }
}
