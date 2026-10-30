package com.velora.api.catalog.importer.service;

import com.velora.api.catalog.domain.Attribute;
import com.velora.api.catalog.domain.AttributeDataType;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryScope;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.domain.FulfillmentType;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductStatus;
import com.velora.api.catalog.domain.ProductVariant;
import com.velora.api.catalog.importer.domain.ImportRowAction;
import com.velora.api.catalog.importer.dto.ImportMessage;
import com.velora.api.catalog.importer.dto.ImportRowData;
import com.velora.api.catalog.importer.service.ImportSheetReader.RawRow;
import com.velora.api.catalog.importer.service.ImportTemplate.Column;
import com.velora.api.catalog.repository.AttributeRepository;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.repository.ProductVariantRepository;
import com.velora.api.common.util.ArabicNormalizer;
import com.velora.api.common.util.MoneyUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a sheet row into a typed {@link ImportRowData} and checks it against the catalogue.
 *
 * <p>Two stages, because they need different things:
 * <ol>
 *   <li>{@link #parse}: only the cells. Types, lengths, required fields, the rules between two
 *       cells of the same row. No database.</li>
 *   <li>{@link #resolve}: the category, the specification attributes, whether the SKU is already
 *       sold, the slug. The commit runs this stage AGAIN, because the catalogue can change between
 *       the preview and the submit.</li>
 * </ol>
 *
 * <p>Severity follows what a cell is for. A problem in what makes the product (SKU, category, name,
 * price, slug, stock) is an ERROR and blocks the whole batch. A problem in a specification cell is a
 * WARNING and the cell is skipped: the furniture sheet carries placeholder text like «Number» and
 * stray values like «10» in a yes/no column, and one of those must not hold back a hundred products.
 */
@Component
public class ImportRowProcessor {

    private static final Pattern ERROR_CELL = Pattern.compile("^#(N/A|REF!|VALUE!|DIV/0!|NAME\\?|NUM!|NULL!)$");
    private static final Pattern SKU = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,59}$");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private static final Set<String> YES = Set.of("نعم", "yes", "true", "1", "y");
    private static final Set<String> NO = Set.of("لا", "no", "false", "0", "n");

    private static final Map<String, Integer> MAX_LENGTH = Map.of(
            "name_ar", 255, "name_en", 255,
            "short_ar", 500,
            "meta_title_ar", 255, "meta_title_en", 255,
            "meta_desc_ar", 500, "meta_desc_en", 500,
            "slug", 180);

    private static final int MAX_LONG_TEXT = 20_000;
    private static final int MAX_SPEC_TEXT = 500;

    private final CategoryRepository categoryRepository;
    private final AttributeRepository attributeRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;

    public ImportRowProcessor(CategoryRepository categoryRepository,
                              AttributeRepository attributeRepository,
                              ProductVariantRepository variantRepository,
                              ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.attributeRepository = attributeRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
    }

    public record Parsed(ImportRowData data, List<ImportMessage> messages) {
    }

    public record Resolved(ImportRowData data, ImportRowAction action, Long productId, Long variantId,
                           List<ImportMessage> messages) {
    }

    /** What the checks need from the catalogue, loaded once per upload or commit. */
    public static class Lookup {
        private final Map<String, List<Category>> categoriesByName;
        private final Map<String, String> attributeProblems;
        private final Map<String, Long> attributeIds;

        Lookup(Map<String, List<Category>> categoriesByName, Map<String, String> attributeProblems,
               Map<String, Long> attributeIds) {
            this.categoriesByName = categoriesByName;
            this.attributeProblems = attributeProblems;
            this.attributeIds = attributeIds;
        }

        public Long attributeId(String code) {
            return attributeIds.get(code);
        }
    }

    public Lookup loadLookup() {
        Map<String, List<Category>> byName = new HashMap<>();
        for (Category category : categoryRepository.findByScopeOrderByDisplayOrderAscIdAsc(CategoryScope.PRODUCT)) {
            for (CategoryTranslation t : category.getTranslations().values()) {
                String key = categoryKey(t.getName());
                if (key == null) {
                    continue;
                }
                List<Category> list = byName.computeIfAbsent(key, k -> new ArrayList<>());
                if (!list.contains(category)) {
                    list.add(category);
                }
            }
        }

        Map<String, Attribute> attributes = new HashMap<>();
        for (Attribute a : attributeRepository.findAll()) {
            attributes.put(a.getCode(), a);
        }
        Map<String, String> problems = new HashMap<>();
        Map<String, Long> ids = new HashMap<>();
        ImportTemplate.SPEC_KINDS.forEach((code, kind) -> {
            Attribute a = attributes.get(code);
            if (a == null) {
                problems.put(code, "The attribute " + code + " does not exist in the database. "
                        + "Apply V23__product_bulk_import.sql");
                return;
            }
            AttributeDataType want = switch (kind) {
                case SPEC_NUMBER -> AttributeDataType.NUMBER;
                case SPEC_BOOLEAN -> AttributeDataType.BOOLEAN;
                default -> AttributeDataType.TEXT;
            };
            if (a.getDataType() != want) {
                problems.put(code, "The attribute " + code + " is " + a.getDataType()
                        + " in the database, but the sheet fills it as " + want);
                return;
            }
            ids.put(code, a.getId());
        });
        return new Lookup(byName, problems, ids);
    }

    /**
     * The sheet writes «كراسي بار» where the shop's category is «كراسي البار». A category name is compared
     * with Arabic spelling variants folded and the definite article «ال» dropped from every word.
     */
    static String categoryKey(String name) {
        String normalized = ArabicNormalizer.normalize(name);
        if (normalized == null) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (String word : normalized.split(" ")) {
            String bare = word.length() > 3 && word.startsWith("ال") ? word.substring(2) : word;
            if (key.length() > 0) {
                key.append(' ');
            }
            key.append(bare);
        }
        return key.toString();
    }

    // ============================================================== stage 1: the cells

    public Parsed parse(RawRow raw) {
        List<ImportMessage> msgs = new ArrayList<>();
        Cells cells = new Cells(raw, msgs);

        if ("1".equals(raw.get(ImportSheetReader.SEO_MISSING))) {
            msgs.add(ImportMessage.error("category", "SEO_ROW_MISSING",
                    "This SKU has no row in the SEO sheet, which carries the category and the slug"));
        }

        String sku = cells.text("sku");
        if (sku != null) {
            sku = sku.toUpperCase(Locale.ROOT);
            if (!SKU.matcher(sku).matches()) {
                msgs.add(ImportMessage.error("sku", "INVALID_SKU",
                        "The SKU may hold only letters, digits, '.', '_' and '-' (up to 60 characters)"));
            }
        }

        String slug = cells.text("slug");
        if (slug != null && !SLUG.matcher(slug).matches()) {
            msgs.add(ImportMessage.error("slug", "INVALID_SLUG",
                    "The slug may hold only lowercase English letters, digits and single hyphens"));
        }

        String nameEn = cells.text("name_en");
        String descEn = cells.text("desc_en");
        String metaTitleEn = cells.text("meta_title_en");
        String metaDescEn = cells.text("meta_desc_en");
        if (nameEn == null && (descEn != null || metaTitleEn != null || metaDescEn != null)) {
            msgs.add(ImportMessage.error("name_en", "ENGLISH_NAME_REQUIRED",
                    "An English name is required when any other English field is filled"));
        }

        String descAr = cells.text("desc_ar");
        if (descAr == null) {
            msgs.add(ImportMessage.warning("desc_ar", "NO_DESCRIPTION",
                    "No Arabic description: the product page will have none"));
        }

        BigDecimal price = cells.money("price");
        if (price != null && price.signum() == 0) {
            msgs.add(ImportMessage.error("price", "PRICE_ZERO", "The price must be above zero"));
        }
        BigDecimal compareAt = cells.money("compare_at");
        if (compareAt != null && price != null && compareAt.compareTo(price) < 0) {
            msgs.add(ImportMessage.error("compare_at", "COMPARE_AT_BELOW_PRICE",
                    "The price before discount cannot be lower than the current price"));
        }

        Map<String, String> specs = new LinkedHashMap<>();
        for (Column column : ImportTemplate.COLUMNS) {
            if (!column.isSpec()) {
                continue;
            }
            String value = cells.spec(column);
            if (value != null) {
                specs.put(column.attributeCode(), value);
            }
        }

        ImportRowData data = new ImportRowData(
                sku, cells.text("category"), null,
                cells.text("name_ar"), nameEn, cells.text("short_ar"), null, descAr, descEn,
                cells.text("meta_title_ar"), metaTitleEn, cells.text("meta_desc_ar"), metaDescEn,
                slug, price, compareAt, cells.integer("stock"), null, null, null, null, null, specs, raw.seoRowNo());
        return new Parsed(data, msgs);
    }

    // ======================================================== stage 2: against the catalogue

    public Resolved resolve(ImportRowData data, Lookup lookup) {
        List<ImportMessage> msgs = new ArrayList<>();

        Long categoryId = null;
        String categoryKey = categoryKey(data.categoryName());
        if (categoryKey != null) {
            List<Category> hits = lookup.categoriesByName.getOrDefault(categoryKey, List.of());
            if (hits.size() == 1) {
                categoryId = hits.get(0).getId();
            } else if (hits.isEmpty()) {
                msgs.add(ImportMessage.error("category", "UNKNOWN_CATEGORY",
                        "No product category is named «" + data.categoryName() + "»"));
            } else {
                msgs.add(ImportMessage.error("category", "AMBIGUOUS_CATEGORY",
                        "More than one category is named «" + data.categoryName() + "»"));
            }
        } else {
            msgs.add(ImportMessage.error("category", "REQUIRED", "This value is required"));
        }

        for (String code : data.specs().keySet()) {
            String problem = lookup.attributeProblems.get(code);
            if (problem != null) {
                msgs.add(ImportMessage.error("spec_" + code.toLowerCase(), "ATTRIBUTE_SETUP", problem));
            }
        }

        // A SKU is unique across the whole shop, archived products included (a SKU is never reused). A row
        // whose SKU is already taken is an error that names who has it; an import only ever creates products.
        FulfillmentType fulfillment = data.fulfillmentType() == null
                ? FulfillmentType.MADE_TO_ORDER : data.fulfillmentType();

        if (data.sku() != null) {
            Optional<ProductVariant> existing = variantRepository.findBySku(data.sku());
            if (existing.isPresent()) {
                Product product = existing.get().getProduct();
                String archived = existing.get().getArchivedAt() != null
                        || product.getStatus() == ProductStatus.ARCHIVED ? " (archived)" : "";
                msgs.add(ImportMessage.error("sku", "SKU_EXISTS",
                        "This SKU is already used by the product «" + product.nameFor("ar") + "»" + archived
                                + ". A SKU is never reused: give this row a new one"));
            }
            if (data.slug() != null && productRepository.existsBySlug(data.slug())) {
                msgs.add(ImportMessage.error("slug", "SLUG_TAKEN",
                        "Another product already uses the slug " + data.slug()));
            }
        }

        if (fulfillment == FulfillmentType.READY_MADE && data.shippingSizeClass() == null) {
            msgs.add(ImportMessage.error("sku", "SIZE_REQUIRED",
                    "A ready-to-ship product needs a shipping size"));
        }

        return new Resolved(data.withResolved(categoryId, fulfillment), ImportRowAction.CREATE, null, null, msgs);
    }

    // ================================================================== labels

    /** The header and column letter of a value's column, for messages. */
    public static String label(String key) {
        Column column = ImportTemplate.column(key);
        return column == null ? key : column.label();
    }

    public static String sheetLabel(ImportTemplate.Sheet sheet) {
        return "«" + ImportTemplate.sheetName(sheet) + "»";
    }

    /** The plain header of a specification's column, shown as its label on the review screen. */
    public static String headerForAttribute(String code) {
        return ImportTemplate.COLUMNS.stream()
                .filter(c -> code.equals(c.attributeCode()))
                .map(Column::header)
                .findFirst()
                .orElse(code);
    }

    // ================================================================== cell helpers

    /** Reads one row's cells by column key, recording what is wrong with them. */
    private static final class Cells {

        private final RawRow row;
        private final List<ImportMessage> msgs;

        /** While a specification is read, a problem is a warning and the cell is skipped. */
        private boolean soft;

        Cells(RawRow row, List<ImportMessage> msgs) {
            this.row = row;
            this.msgs = msgs;
        }

        private void problem(String column, String code, String message) {
            msgs.add(soft ? ImportMessage.warning(column, code, message + " (the value is skipped)")
                    : ImportMessage.error(column, code, message));
        }

        private boolean required(String key) {
            Column column = ImportTemplate.column(key);
            return column != null && column.required();
        }

        /** The cell's text, or null when it is empty or unusable (the reason is already recorded). */
        private String raw(String key) {
            String v = row.get(key);
            if (v == null || v.isEmpty()) {
                if (required(key)) {
                    problem(key, "REQUIRED", "This value is required");
                }
                return null;
            }
            if (ERROR_CELL.matcher(v).matches()) {
                problem(key, "INVALID_CELL", "The cell holds an Excel error (" + v + ")");
                return null;
            }
            return v;
        }

        String text(String key) {
            String v = raw(key);
            if (v == null) {
                return null;
            }
            int max = MAX_LENGTH.getOrDefault(key, MAX_LONG_TEXT);
            if (v.length() > max) {
                problem(key, "TOO_LONG", "At most " + max + " characters (this has " + v.length() + ")");
                return null;
            }
            return v;
        }

        BigDecimal money(String key) {
            BigDecimal b = decimal(key, raw(key));
            if (b == null) {
                return null;
            }
            if (b.stripTrailingZeros().scale() > MoneyUtils.SCALE) {
                problem(key, "TOO_MANY_DECIMALS", "At most 4 decimal places");
                return null;
            }
            return MoneyUtils.round(b);
        }

        Integer integer(String key) {
            BigDecimal b = decimal(key, raw(key));
            if (b == null) {
                return null;
            }
            if (b.stripTrailingZeros().scale() > 0) {
                problem(key, "NOT_WHOLE", "Must be a whole number");
                return null;
            }
            try {
                return b.intValueExact();
            } catch (ArithmeticException e) {
                problem(key, "TOO_LARGE", "The number is too large");
                return null;
            }
        }

        /** A specification cell, as the text that is stored. */
        String spec(Column column) {
            soft = true;
            try {
                String v = raw(column.key());
                if (v == null) {
                    return null;
                }
                return switch (column.kind()) {
                    case SPEC_NUMBER -> specNumber(column, v);
                    case SPEC_BOOLEAN -> specBoolean(column, v);
                    default -> {
                        if (v.length() > MAX_SPEC_TEXT) {
                            problem(column.key(), "TOO_LONG", "At most " + MAX_SPEC_TEXT + " characters");
                            yield null;
                        }
                        yield v;
                    }
                };
            } finally {
                soft = false;
            }
        }

        private String specBoolean(Column column, String v) {
            String n = v.trim().toLowerCase(Locale.ROOT);
            if (YES.contains(n)) {
                return ImportTemplate.YES;
            }
            if (NO.contains(n)) {
                return ImportTemplate.NO;
            }
            problem(column.key(), "INVALID_VALUE",
                    "«" + v + "» is not «" + ImportTemplate.YES + "» or «" + ImportTemplate.NO + "»");
            return null;
        }

        private String specNumber(Column column, String v) {
            BigDecimal b = decimal(column.key(), v);
            if (b == null) {
                return null;
            }
            if ("COMFORT_LEVEL".equals(column.attributeCode())
                    && (b.compareTo(BigDecimal.ONE) < 0 || b.compareTo(BigDecimal.TEN) > 0)) {
                problem(column.key(), "OUT_OF_RANGE", "Must be between 1 and 10");
                return null;
            }
            return b.stripTrailingZeros().toPlainString();
        }

        /** A non-negative decimal from text; Arabic digits and thousands separators are accepted. */
        private BigDecimal decimal(String key, String v) {
            if (v == null) {
                return null;
            }
            String s = ArabicNormalizer.convertArabicDigits(v)
                    .replace(",", "").replace("٬", "").replace("٫", ".").replace(" ", "");
            BigDecimal b;
            try {
                b = new BigDecimal(s);
            } catch (NumberFormatException e) {
                problem(key, "INVALID_NUMBER", "«" + v + "» is not a number");
                return null;
            }
            if (b.signum() < 0) {
                problem(key, "NEGATIVE", "Cannot be negative");
                return null;
            }
            if (b.precision() - b.scale() > 15) {
                problem(key, "TOO_LARGE", "The number is too large");
                return null;
            }
            return b;
        }
    }
}
