package com.velora.api.catalog.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One locale's translated content for a category, as seen by staff — the read-side
 * counterpart of the {@code TranslationRequest} entries in
 * {@link CategorySaveRequest#translations()}, so the admin form can load a category,
 * edit a field or two, and send the array straight back.
 *
 * <p>No {@code shortDescription}: {@code CategoryTranslation} has no such column —
 * unlike the shared product/category {@code TranslationRequest}, which carries the
 * field only because products use it.
 */
@Schema(description = "Translated content for one category locale, as seen by staff")
public record CategoryTranslationResponse(
        String locale,
        String name,
        String description,
        String metaTitle,
        String metaDescription
) {
}
