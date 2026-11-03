package com.velora.api.catalog.review.web;

import com.velora.api.catalog.review.dto.ProductReviewsResponse;
import com.velora.api.catalog.review.service.ProductReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Product reviews", description = "The approved reviews of a product. Public.")
@RestController
@RequestMapping("/api/v1/products/{slug}/reviews")
public class ProductReviewController {

    private final ProductReviewService reviewService;

    public ProductReviewController(ProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "Approved reviews of a product, newest first",
            description = "Only APPROVED reviews appear. Includes the average and how many reviews gave each star count.")
    @GetMapping
    public ProductReviewsResponse list(@PathVariable String slug,
                                       @ParameterObject @PageableDefault(size = 10) Pageable pageable) {
        return reviewService.forProduct(slug, pageable);
    }
}
