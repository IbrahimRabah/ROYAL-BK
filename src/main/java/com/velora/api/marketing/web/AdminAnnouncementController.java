package com.velora.api.marketing.web;

import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.marketing.dto.AdminAnnouncementResponse;
import com.velora.api.marketing.dto.AnnouncementReorderRequest;
import com.velora.api.marketing.dto.AnnouncementSaveRequest;
import com.velora.api.marketing.service.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Announcements", description = "The message strip above the storefront. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/announcements")
public class AdminAnnouncementController {

    private final AnnouncementService service;

    public AdminAnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @Operation(summary = "Every announcement, in display order", description = "`showingNow` says whether visitors see it at this moment.")
    @GetMapping
    public List<AdminAnnouncementResponse> list() {
        return service.list();
    }

    @Operation(summary = "Create an announcement",
            description = "The Arabic text is required unless it is linked to a discount, which then writes it.")
    @PostMapping
    public ResponseEntity<AdminAnnouncementResponse> create(@Valid @RequestBody AnnouncementSaveRequest request,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, principal.id()));
    }

    @Operation(summary = "Replace an announcement")
    @PutMapping("/{id}")
    public AdminAnnouncementResponse update(@PathVariable Long id, @Valid @RequestBody AnnouncementSaveRequest request,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return service.update(id, request, principal.id());
    }

    @Operation(summary = "Switch on")
    @PostMapping("/{id}/on")
    public AdminAnnouncementResponse on(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return service.setActive(id, true, principal.id());
    }

    @Operation(summary = "Switch off")
    @PostMapping("/{id}/off")
    public AdminAnnouncementResponse off(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return service.setActive(id, false, principal.id());
    }

    @Operation(summary = "Delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        service.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Put the messages in the order they rotate", description = "The ids in the order wanted.")
    @PutMapping("/reorder")
    public Map<String, Integer> reorder(@Valid @RequestBody AnnouncementReorderRequest request,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return Map.of("reordered", service.reorder(request.ids(), principal.id()));
    }
}
