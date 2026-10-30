package com.velora.api.catalog.importer.dto;

import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.ShippingSizeClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;

/**
 * One sheet row after parsing: the typed values, ready to commit. It is what the review screen
 * shows and what is stored in {@code import_batch_row.data}, so the commit works from exactly
 * what the reviewer saw rather than parsing the file a second time.
 *
 * <p>A null field means the cell was empty. On a new product that means "use the default"; on an
 * existing one it means "leave it as it is".
 */
@Schema(description = "A parsed product row")
public record ImportRowData(
        String sku,
        String categoryName,
        @Schema(description = "Set once the category name has been matched") Long categoryId,
        String nameAr,
        String nameEn,
        String shortAr,
        String shortEn,
        String descAr,
        String descEn,
        String metaTitleAr,
        String metaTitleEn,
        String metaDescAr,
        String metaDescEn,
        String slug,
        @Schema(description = "Tax-inclusive, what the customer pays") BigDecimal price,
        BigDecimal compareAtPrice,
        Integer stock,
        FulfillmentType fulfillmentType,
        ShippingSizeClass shippingSizeClass,
        Boolean requiresAssembly,
        BigDecimal assemblyFee,
        Integer weightGrams,
        @Schema(description = "Specification values by attribute code") Map<String, String> specs,
        @Schema(description = "The row of this SKU in the SEO sheet of the uploaded workbook, for cell references")
        Integer seoRowNo
) {

    public ImportRowData withResolved(Long id, FulfillmentType fulfillment) {
        return new ImportRowData(sku, categoryName, id, nameAr, nameEn, shortAr, shortEn, descAr, descEn,
                metaTitleAr, metaTitleEn, metaDescAr, metaDescEn, slug, price, compareAtPrice, stock,
                fulfillment, shippingSizeClass, requiresAssembly, assemblyFee, weightGrams, specs, seoRowNo);
    }
}
