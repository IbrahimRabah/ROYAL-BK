package com.velora.api.catalog.importer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one definition of the product import workbook, which is the furniture sheet itself:
 * the main sheet «كرسي البار» (44 columns) and the «SEO» sheet (25 columns), joined by SKU.
 *
 * <p>The template people download is a trimmed copy of that workbook with headers only (no data rows), and an upload
 * is accepted only when both sheets carry exactly these header rows. The header lists below are copied
 * from the workbook, typos included («الرئسية», «أقصي»): they are the contract, so they must not be
 * "corrected" here without correcting the template resource too. {@code ImportTemplateTest} checks the
 * two against each other.
 *
 * <p>Bump {@link #VERSION} when a column is added, removed, renamed or moved.
 */
public final class ImportTemplate {

    public static final String VERSION = "2";

    public static final String SHEET_MAIN = "كرسي البار";
    public static final String SHEET_SEO = "SEO";

    /** The classpath location of the downloadable template. */
    public static final String RESOURCE = "import/product-import-template.xlsx";

    /** One import accepts at most this many product rows. */
    public static final int MAX_ROWS = 2000;

    public static final String YES = "نعم";
    public static final String NO = "لا";

    public enum Sheet { MAIN, SEO }

    public enum Kind {
        SKU, CATEGORY, TEXT, LONG_TEXT, SLUG, MONEY, INTEGER, SPEC_TEXT, SPEC_NUMBER, SPEC_BOOLEAN
    }

    static final String[] MAIN_HEADERS = {
            "رقم الموديل",
            "اسم المنتج",
            "كود الصور",
            "عدد القطع الرئسية",
            "الخامة الرئيسية",
            "الخامة الرئيسية للرجل",
            "خامة التنجيد",
            "كثافة خامة التنجيد",
            "نوع القماش",
            "اللون الرئيسي",
            "اللون الرئسي بالتفصيل",
            "اللون الرئيسي للرجل",
            "لون الرجل بالتفصيل",
            "العرض (سم)",
            "الارتفاع (سم)",
            "العمق (سم)",
            "ارتفاع القعدة (سم)",
            "عمق القاعدة (سم)",
            "ارتفاع مسند الذراع (سم)",
            "الطول من الذراع للأرض (سم)",
            "مستوى الراحة",
            "يتضمن وظيفة الدوران",
            "يتضمن خاصية المساج",
            "مسند قدم قابل للتعديل",
            "مسند رأس قابل للتعديل",
            "قابل للتمديد",
            "ابعاد التمديد (سم)",
            "يتضمن ميكانيزم",
            "بلد الميكانيزم",
            "أقصي وزن للمستخدم (كجم)",
            "الضمان (بالسنة)",
            "السعر قبل الخصم",
            "السعر بعد الخصم",
            "كمية المخزون",
            "بلد المنشأ",
            "وصف المنتج",
            "وصف المنتج بالانجليزي",
            "ملاحظات البائع",
            "",
            "السعر بعد الخصم",
            "العنوان SEO (عربي)",
            "SEO Title (English)",
            "الوصف SEO (عربي)",
            "SEO Description (English)"
    };

    static final String[] SEO_HEADERS = {
            "SKU",
            "القسم",
            "Category",
            "نوع المنتج",
            "العنوان SEO (عربي)",
            "SEO Title (English)",
            "العرض (سم)",
            "العمق (سم)",
            "الارتفاع (سم)",
            "مقاسات إضافية (أطقم / كراسي السفرة / دائري)",
            "اللون",
            "Color",
            "السعر بعد الخصم (ج.م)",
            "الوصف SEO (عربي)",
            "SEO Description (English)",
            "Meta Title (عربي)",
            "Meta Description (عربي)",
            "Meta Title (EN)",
            "Meta Description (EN)",
            "URL Slug",
            "كلمات مفتاحية (عربي)",
            "Keywords (EN)",
            "حروف Meta Title عربي",
            "حروف Meta Desc عربي",
            "ملاحظات للمراجعة"
    };

    /**
     * @param key           stable internal name of the value
     * @param index         zero-based column in its sheet
     * @param attributeCode the {@code attribute.code} a SPEC_* column is stored under, else null
     */
    public record Column(String key, Sheet sheet, int index, Kind kind, boolean required, String attributeCode) {

        public boolean isSpec() {
            return attributeCode != null;
        }

        /** The header text exactly as the sheet carries it. */
        public String header() {
            return headers(sheet)[index];
        }

        /** The header and its column letter: several headers repeat, so the text alone is ambiguous. */
        public String label() {
            String header = header();
            String letter = columnLetter(index);
            return (header.isBlank() ? "" : header + " ") + "(" + (sheet == Sheet.SEO ? "SEO!" : "") + letter + ")";
        }
    }

    private static Column main(String key, int index, Kind kind, boolean required) {
        return new Column(key, Sheet.MAIN, index, kind, required, null);
    }

    private static Column seo(String key, int index, Kind kind) {
        return new Column(key, Sheet.SEO, index, kind, false, null);
    }

    private static Column spec(String code, int index, Kind kind) {
        return new Column("spec_" + code.toLowerCase(), Sheet.MAIN, index, kind, false, code);
    }

    /**
     * Every column that is read. Columns not listed (the product-name column, the image code, the seller's
     * notes, the SEO sheet's keyword and size columns) are ignored on purpose; they are still part of the
     * template, so a file missing them is still refused.
     */
    public static final List<Column> COLUMNS = List.of(
            main("sku", 0, Kind.SKU, true),
            seo("category", 1, Kind.CATEGORY),

            main("name_ar", 40, Kind.TEXT, true),
            main("name_en", 41, Kind.TEXT, false),
            seo("name_ar_seo", 4, Kind.TEXT),
            seo("name_en_seo", 5, Kind.TEXT),
            main("short_ar", 35, Kind.TEXT, false),
            main("desc_ar", 42, Kind.LONG_TEXT, false),
            main("desc_en", 43, Kind.LONG_TEXT, false),
            main("desc_en_alt", 36, Kind.LONG_TEXT, false),
            seo("desc_ar_seo", 13, Kind.LONG_TEXT),
            seo("desc_en_seo", 14, Kind.LONG_TEXT),
            seo("meta_title_ar", 15, Kind.TEXT),
            seo("meta_title_en", 17, Kind.TEXT),
            seo("meta_desc_ar", 16, Kind.TEXT),
            seo("meta_desc_en", 18, Kind.TEXT),
            seo("slug", 19, Kind.SLUG),

            main("price", 39, Kind.MONEY, true),
            seo("price_seo", 12, Kind.MONEY),
            main("price_ag", 32, Kind.MONEY, false),
            main("compare_at", 31, Kind.MONEY, false),
            main("stock", 33, Kind.INTEGER, false),

            spec("PIECES_COUNT", 3, Kind.SPEC_NUMBER),
            spec("MATERIAL", 4, Kind.SPEC_TEXT),
            spec("LEG_MATERIAL", 5, Kind.SPEC_TEXT),
            spec("UPHOLSTERY_MATERIAL", 6, Kind.SPEC_TEXT),
            spec("UPHOLSTERY_DENSITY", 7, Kind.SPEC_NUMBER),
            spec("FABRIC_TYPE", 8, Kind.SPEC_TEXT),
            spec("BODY_COLOR", 9, Kind.SPEC_TEXT),
            spec("BODY_COLOR_DETAIL", 10, Kind.SPEC_TEXT),
            spec("LEG_COLOR", 11, Kind.SPEC_TEXT),
            spec("LEG_COLOR_DETAIL", 12, Kind.SPEC_TEXT),
            spec("WIDTH_CM", 13, Kind.SPEC_NUMBER),
            spec("HEIGHT_CM", 14, Kind.SPEC_NUMBER),
            spec("DEPTH_CM", 15, Kind.SPEC_NUMBER),
            spec("SEAT_HEIGHT_CM", 16, Kind.SPEC_NUMBER),
            spec("SEAT_DEPTH_CM", 17, Kind.SPEC_NUMBER),
            spec("ARMREST_HEIGHT_CM", 18, Kind.SPEC_NUMBER),
            spec("FLOOR_TO_ARM_CM", 19, Kind.SPEC_NUMBER),
            spec("COMFORT_LEVEL", 20, Kind.SPEC_NUMBER),
            spec("SWIVEL", 21, Kind.SPEC_BOOLEAN),
            spec("MASSAGE", 22, Kind.SPEC_BOOLEAN),
            spec("ADJUSTABLE_FOOTREST", 23, Kind.SPEC_BOOLEAN),
            spec("ADJUSTABLE_HEADREST", 24, Kind.SPEC_BOOLEAN),
            spec("EXTENDABLE", 25, Kind.SPEC_BOOLEAN),
            spec("EXTENSION_DIMENSIONS", 26, Kind.SPEC_TEXT),
            spec("HAS_MECHANISM", 27, Kind.SPEC_BOOLEAN),
            spec("MECHANISM_COUNTRY", 28, Kind.SPEC_TEXT),
            spec("MAX_USER_WEIGHT_KG", 29, Kind.SPEC_NUMBER),
            spec("WARRANTY", 30, Kind.SPEC_TEXT),
            spec("COUNTRY_OF_ORIGIN", 34, Kind.SPEC_TEXT));

    /** The attribute codes the import writes, with the kind each must have in the database. */
    public static final Map<String, Kind> SPEC_KINDS = COLUMNS.stream()
            .filter(Column::isSpec)
            .collect(Collectors.toUnmodifiableMap(Column::attributeCode, Column::kind));

    /** Where the SKU of a SEO row is: the key the two sheets are joined on. */
    public static final int SEO_SKU_INDEX = 0;

    private ImportTemplate() {
    }

    public static String[] headers(Sheet sheet) {
        return sheet == Sheet.MAIN ? MAIN_HEADERS : SEO_HEADERS;
    }

    public static String sheetName(Sheet sheet) {
        return sheet == Sheet.MAIN ? SHEET_MAIN : SHEET_SEO;
    }

    /** The column definition for a key; the processor reports problems against its header. */
    public static Column column(String key) {
        for (Column c : COLUMNS) {
            if (c.key().equals(key)) {
                return c;
            }
        }
        return null;
    }

    public static List<Column> columns(Sheet sheet) {
        List<Column> list = new ArrayList<>();
        for (Column c : COLUMNS) {
            if (c.sheet() == sheet) {
                list.add(c);
            }
        }
        return list;
    }

    /** Collapses runs of whitespace so a header copied through email or chat still compares equal. */
    public static String normalizeHeader(String header) {
        return header == null ? "" : header.replace(' ', ' ').trim().replaceAll("\\s+", " ");
    }

    public static String columnLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        }
        return sb.toString();
    }
}
