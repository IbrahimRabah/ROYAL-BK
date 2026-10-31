package com.velora.api.catalog.imageimport.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Which files belong to which product, and what would happen to each, before anything is uploaded")
public record ImageMatchResponse(
        String mode,
        Summary summary,
        @Schema(description = "One entry per matched SKU, ordered by SKU") List<ProductMatch> products,
        @Schema(description = "Files that cannot all be used: two files for the same place in one gallery")
        List<Conflict> conflicts,
        @Schema(description = "Files that match no product, are not pictures, or are in an unsupported format")
        List<Unmatched> unmatched,
        @Schema(description = "Products that have no image and none in this selection") WithoutImage productsWithoutImage
) {

    public record Summary(
            int files,
            @Schema(description = "Files that will be uploaded") int toUpload,
            @Schema(description = "Products left alone because they already have images (mode SKIP)")
            int productsSkipped,
            int products,
            int conflicts,
            int unmatched,
            int productsWithoutImage
    ) {
    }

    public record ProductMatch(
            String sku,
            Long productId,
            @Schema(description = "Set only when the product has several variants; the image then belongs to that variant")
            Long variantId,
            String nameAr,
            String nameEn,
            String categoryName,
            String status,
            int existingImageCount,
            @Schema(description = "ATTACH, SKIP_HAS_IMAGES (mode SKIP and it has images) or BLOCKED") String action,
            @Schema(description = "PRODUCT_ARCHIVED when action is BLOCKED") String blockedReason,
            List<MatchedFile> files
    ) {
    }

    public record MatchedFile(
            String name,
            String folder,
            @Schema(description = "1 is the main image, 2 and up are SKU_2, SKU_3 …") int position,
            long sizeBytes,
            @Schema(description = "False when this file will not be uploaded") boolean upload,
            @Schema(description = "Why not: DUPLICATE_POSITION, TOO_MANY_IMAGES, or null") String problem
    ) {
    }

    public record Conflict(
            String sku,
            @Schema(description = "DUPLICATE_POSITION: the files below all claim the same place") String reason,
            List<ConflictFile> files
    ) {
    }

    public record ConflictFile(String name, String folder, int position, long sizeBytes) {
    }

    public record Unmatched(
            String name,
            String folder,
            long sizeBytes,
            @Schema(description = "NO_PRODUCT, NOT_AN_IMAGE, UNSUPPORTED_FORMAT (e.g. HEIC) or AMBIGUOUS_NAME") String reason,
            @Schema(description = "The SKU the name does match, when the format is the problem") String sku,
            @Schema(description = "A near miss, for the person to rename; never used by itself") String suggestionSku,
            Integer suggestionPosition
    ) {
    }

    public record WithoutImage(int total, @Schema(description = "The first few, for the report") List<Item> items) {
    }

    public record Item(Long productId, String sku, String nameAr, String nameEn) {
    }
}
