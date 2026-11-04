package com.velora.api.seo.web;

import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.seo.domain.FaqTopic;
import com.velora.api.seo.dto.FaqResponse;
import com.velora.api.seo.dto.PublicSeoPageResponse;
import com.velora.api.seo.service.FaqService;
import com.velora.api.seo.service.SeoAdminService;
import com.velora.api.seo.service.SitemapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "SEO and FAQ (public)", description = "Page texts, published FAQs, sitemap and robots. Public.")
@RestController
@RequestMapping("/api/v1")
public class SeoPublicController {

    private final FaqService faqService;
    private final SeoAdminService seoService;
    private final SitemapService sitemapService;

    public SeoPublicController(FaqService faqService, SeoAdminService seoService, SitemapService sitemapService) {
        this.faqService = faqService;
        this.seoService = seoService;
        this.sitemapService = sitemapService;
    }

    @Operation(summary = "Published FAQs of a product, in order")
    @GetMapping("/products/{slug}/faqs")
    public List<FaqResponse> productFaqs(@PathVariable String slug, HttpServletRequest request) {
        return faqService.forProduct(slug, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Published FAQs of a category, in order")
    @GetMapping("/categories/{id}/faqs")
    public List<FaqResponse> categoryFaqs(@PathVariable Long id, HttpServletRequest request) {
        return faqService.forCategory(id, LocaleResolver.resolve(request));
    }

    @Operation(summary = "The general FAQ, optionally one topic",
            description = "Topics: GENERAL, ORDERS, SHIPPING, PAYMENT, RETURNS, CUSTOM_WORK, CHECKOUT.")
    @GetMapping("/faqs")
    public List<FaqResponse> general(@RequestParam(required = false) FaqTopic topic, HttpServletRequest request) {
        return faqService.general(topic, LocaleResolver.resolve(request));
    }

    @Operation(summary = "Title, description and noindex of a static page",
            description = "Keys: HOME, PRODUCTS, ABOUT, CONTACT, PORTFOLIO, CUSTOM_REQUEST, FAQ. "
                    + "Absent fields mean: use the built-in text.")
    @GetMapping("/seo/pages/{key}")
    public PublicSeoPageResponse page(@PathVariable String key, HttpServletRequest request) {
        return seoService.publicPage(key, LocaleResolver.resolve(request));
    }

    @Operation(summary = "sitemap.xml", description = "Active products, categories with products, public pages.")
    @GetMapping(value = "/seo/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(sitemapService.sitemap());
    }

    @Operation(summary = "robots.txt")
    @GetMapping(value = "/seo/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(sitemapService.robots());
    }
}
