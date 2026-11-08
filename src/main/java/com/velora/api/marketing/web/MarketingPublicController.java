package com.velora.api.marketing.web;

import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.marketing.dto.PublicAnnouncementResponse;
import com.velora.api.marketing.dto.PublicPromotionResponse;
import com.velora.api.marketing.service.AnnouncementService;
import com.velora.api.marketing.service.PromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Announcements and sales (public)", description = "The message strip and the running sale banners. Public.")
@RestController
@RequestMapping("/api/v1")
public class MarketingPublicController {

    private final AnnouncementService announcementService;
    private final PromotionService promotionService;

    public MarketingPublicController(AnnouncementService announcementService, PromotionService promotionService) {
        this.announcementService = announcementService;
        this.promotionService = promotionService;
    }

    @Operation(summary = "The announcement messages to show right now, in order",
            description = "Only active messages inside their dates; one linked to a discount appears only while that "
                    + "discount is running, and writes its own text when none was typed.")
    @GetMapping("/announcements/active")
    public List<PublicAnnouncementResponse> announcements(HttpServletRequest request) {
        return announcementService.active(LocaleResolver.resolve(request));
    }

    @Operation(summary = "The discounts running right now, biggest first",
            description = "For the sale banners. `landingPath` is where the banner leads: /products?onSale=true for "
                    + "all products, the category page, or the product page.")
    @GetMapping("/promotions/active")
    public List<PublicPromotionResponse> promotions(HttpServletRequest request) {
        return promotionService.running(LocaleResolver.resolve(request));
    }
}
