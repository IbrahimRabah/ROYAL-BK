package com.velora.api.inventory.web;

import com.velora.api.common.dto.PageResponse;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.inventory.dto.InventoryAdminResponse;
import com.velora.api.inventory.dto.StockAdjustRequest;
import com.velora.api.inventory.dto.StockMovementResponse;
import com.velora.api.inventory.dto.StockReceiveRequest;
import com.velora.api.inventory.service.InventoryAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Inventory", description = "Stock control. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class AdminInventoryController {

    private final InventoryAdminService inventoryService;

    public AdminInventoryController(InventoryAdminService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Operation(summary = "Full inventory list",
            description = "Paginated and searchable — every variant's stock position in one call. "
                    + "Sorted by available quantity ascending by default, so the items closest to "
                    + "running out are first.")
    @GetMapping
    public PageResponse<InventoryAdminResponse> list(
            @Parameter(description = "Matches SKU or product name, in any locale")
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean lowStockOnly,
            @RequestParam(required = false) Boolean outOfStockOnly,
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "available_asc (default) or available_desc")
            @RequestParam(required = false, defaultValue = "available_asc") String sort,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return inventoryService.list(q, lowStockOnly, outOfStockOnly, categoryId, sort, pageable);
    }

    @Operation(summary = "Stock position for one variant")
    @GetMapping("/{variantId}")
    public InventoryAdminResponse get(@PathVariable Long variantId) {
        return inventoryService.get(variantId);
    }

    @Operation(summary = "Low stock report", description = "At or below the variant's threshold")
    @GetMapping("/low-stock")
    public List<InventoryAdminResponse> lowStock() {
        return inventoryService.lowStock();
    }

    @Operation(summary = "Receive goods from a supplier")
    @PostMapping("/{variantId}/receive")
    public InventoryAdminResponse receive(@PathVariable Long variantId,
                                          @Valid @RequestBody StockReceiveRequest request,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return inventoryService.receive(variantId, request,
                principal == null ? null : principal.id());
    }

    @Operation(summary = "Manual stock adjustment",
            description = """
                    A reason is REQUIRED. An unexplained stock change is indistinguishable
                    from theft, and nobody remembers six months later.

                    Refused if it would take stock below the quantity already reserved for
                    orders in progress.
                    """)
    @PostMapping("/{variantId}/adjust")
    public InventoryAdminResponse adjust(@PathVariable Long variantId,
                                         @Valid @RequestBody StockAdjustRequest request,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return inventoryService.adjust(variantId, request,
                principal == null ? null : principal.id());
    }

    @Operation(summary = "Stock movement history",
            description = "The append-only ledger. Omit variantId for everything.")
    @GetMapping("/movements")
    public PageResponse<StockMovementResponse> movements(
            @RequestParam(required = false) Long variantId,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return inventoryService.movements(variantId, pageable);
    }
}
