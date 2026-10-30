package com.velora.api.catalog.importer.service;

import com.velora.api.catalog.importer.dto.ImportRowData;
import com.velora.api.catalog.importer.service.ImportTemplate.Column;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A parsed row as the flat {@code field key -> text} map the row editor works with, and the list of
 * keys an edit may touch. The keys are the template's column keys, which are also the {@code field}
 * of every finding, so a finding points at the input that fixes it.
 *
 * <p>Only what staff can sensibly correct is editable. The values the reader derives from where a cell
 * sits (the SEO fallbacks, the duplicate price columns) are already folded into one value each by the
 * time a row is stored, so the editor shows and changes that one value.
 */
final class ImportRowValues {

    /** The non-specification values an edit may change. */
    private static final Set<String> CORE = Set.of(
            "sku", "category", "name_ar", "name_en", "short_ar", "desc_ar", "desc_en",
            "meta_title_ar", "meta_title_en", "meta_desc_ar", "meta_desc_en",
            "slug", "price", "compare_at", "stock");

    private ImportRowValues() {
    }

    static boolean isEditable(String key) {
        if (CORE.contains(key)) {
            return true;
        }
        Column column = ImportTemplate.column(key);
        return column != null && column.isSpec();
    }

    /** The row's current values, empty text for what is not filled. */
    static Map<String, String> of(ImportRowData d) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("sku", text(d.sku()));
        v.put("category", text(d.categoryName()));
        v.put("name_ar", text(d.nameAr()));
        v.put("name_en", text(d.nameEn()));
        v.put("short_ar", text(d.shortAr()));
        v.put("desc_ar", text(d.descAr()));
        v.put("desc_en", text(d.descEn()));
        v.put("meta_title_ar", text(d.metaTitleAr()));
        v.put("meta_title_en", text(d.metaTitleEn()));
        v.put("meta_desc_ar", text(d.metaDescAr()));
        v.put("meta_desc_en", text(d.metaDescEn()));
        v.put("slug", text(d.slug()));
        v.put("price", d.price() == null ? "" : d.price().stripTrailingZeros().toPlainString());
        v.put("compare_at", d.compareAtPrice() == null ? "" : d.compareAtPrice().stripTrailingZeros().toPlainString());
        v.put("stock", d.stock() == null ? "" : String.valueOf(d.stock()));
        for (Column column : ImportTemplate.COLUMNS) {
            if (column.isSpec()) {
                v.put(column.key(), text(d.specs().get(column.attributeCode())));
            }
        }
        return v;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
