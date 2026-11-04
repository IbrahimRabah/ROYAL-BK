package com.velora.api.seo.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryTranslation;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductTranslation;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.common.dto.PageResponse;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.seo.domain.SeoPage;
import com.velora.api.seo.dto.PublicSeoPageResponse;
import com.velora.api.seo.dto.SeoMetaRequest;
import com.velora.api.seo.dto.SeoOverviewResponse;
import com.velora.api.seo.dto.SeoPageRequest;
import com.velora.api.seo.dto.SeoPageResponse;
import com.velora.api.seo.dto.SeoRowResponse;
import com.velora.api.seo.repository.SeoPageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the SEO team sees and edits: the health of the meta texts, the meta texts of products and categories, and
 * the texts of the static pages. Only the SEO fields are touched; a product's name, price and slug are not
 * reachable from here, which is what makes it safe to give the screen to someone who is not an administrator.
 *
 * <p>Health is measured on the Arabic text, the language the shop is indexed in. Lists use SQL because they
 * join the translation, the FAQ count and a duplicate check in one page-sized query.
 */
@Service
public class SeoAdminService {

    /** Google shows about this many characters before it cuts the text. */
    public static final int TITLE_LIMIT = 60;
    public static final int DESCRIPTION_LIMIT = 155;

    public enum Filter { ALL, MISSING_TITLE, MISSING_DESCRIPTION, TOO_LONG, DUPLICATE, NO_FAQ }

    private static final String BLANK_TITLE = "(t.meta_title IS NULL OR LTRIM(RTRIM(t.meta_title)) = N'')";
    private static final String BLANK_DESCRIPTION = "(t.meta_description IS NULL OR LTRIM(RTRIM(t.meta_description)) = N'')";

    private final JdbcTemplate jdbc;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SeoPageRepository pageRepository;
    private final AuditService auditService;

    public SeoAdminService(JdbcTemplate jdbc, ProductRepository productRepository,
                           CategoryRepository categoryRepository, SeoPageRepository pageRepository,
                           AuditService auditService) {
        this.jdbc = jdbc;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.pageRepository = pageRepository;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ overview

    @Transactional(readOnly = true)
    public SeoOverviewResponse overview() {
        String live = "FROM product p JOIN product_translation t ON t.product_id = p.id AND t.locale = 'ar' "
                + "WHERE p.archived_at IS NULL AND p.status = 'ACTIVE'";
        long total = count("SELECT COUNT(*) " + live);
        SeoOverviewResponse.Products products = new SeoOverviewResponse.Products(
                total,
                count("SELECT COUNT(*) " + live + " AND " + BLANK_TITLE),
                count("SELECT COUNT(*) " + live + " AND " + BLANK_DESCRIPTION),
                count("SELECT COUNT(*) " + live + " AND LEN(t.meta_title) > " + TITLE_LIMIT),
                count("SELECT COUNT(*) " + live + " AND LEN(t.meta_description) > " + DESCRIPTION_LIMIT),
                count("SELECT COUNT(*) " + live + " AND " + duplicateExpression("p", "t") + " = 1"),
                count("SELECT COUNT(*) " + live + " AND " + noFaq("PRODUCT", "p.id")));

        String cats = "FROM category c JOIN category_translation t ON t.category_id = c.id AND t.locale = 'ar' "
                + "WHERE c.is_active = 1 AND c.scope = 'PRODUCT'";
        SeoOverviewResponse.Categories categories = new SeoOverviewResponse.Categories(
                count("SELECT COUNT(*) " + cats),
                count("SELECT COUNT(*) " + cats + " AND " + BLANK_TITLE),
                count("SELECT COUNT(*) " + cats + " AND " + BLANK_DESCRIPTION),
                count("SELECT COUNT(*) " + cats + " AND " + noFaq("CATEGORY", "c.id")));

        SeoOverviewResponse.Pages pages = new SeoOverviewResponse.Pages(
                count("SELECT COUNT(*) FROM seo_page"),
                count("SELECT COUNT(*) FROM seo_page WHERE title_ar IS NULL OR LTRIM(RTRIM(title_ar)) = N''"),
                count("SELECT COUNT(*) FROM seo_page WHERE description_ar IS NULL OR LTRIM(RTRIM(description_ar)) = N''"));

        SeoOverviewResponse.Faqs faqs = new SeoOverviewResponse.Faqs(
                count("SELECT COUNT(*) FROM faq_item WHERE status = 'PUBLISHED'"),
                count("SELECT COUNT(*) FROM faq_item WHERE status = 'DRAFT'"));
        return new SeoOverviewResponse(products, categories, pages, faqs);
    }

    // ------------------------------------------------------------------ products

    @Transactional(readOnly = true)
    public PageResponse<SeoRowResponse> products(String query, Filter filter, String status, Pageable pageable) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE p.archived_at IS NULL");
        if (status != null && !status.isBlank()) {
            where.append(" AND p.status = ?");
            args.add(status);
        }
        searchClause(where, args, query, "t.name", "p.slug");
        filterClause(where, filter, true);
        return page(productSelect(where.toString()), productCount(where.toString()), args, pageable,
                (rs, n) -> new SeoRowResponse(rs.getLong("id"), rs.getString("slug"), rs.getString("status"),
                        rs.getString("name"), rs.getString("mta"), rs.getString("mda"), rs.getString("mte"),
                        rs.getString("mde"), rs.getLong("faq"), rs.getInt("dup") == 1));
    }

    @Transactional
    public SeoRowResponse updateProduct(Long id, SeoMetaRequest request, Long actorId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        ProductTranslation ar = product.getTranslations().get("ar");
        if (ar == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The product has no Arabic text to edit");
        }
        ProductTranslation en = product.getTranslations().get("en");

        Changes changes = new Changes();
        ar.setMetaTitle(changes.set("metaTitleAr", ar.getMetaTitle(), request.metaTitleAr()));
        ar.setMetaDescription(changes.set("metaDescriptionAr", ar.getMetaDescription(), request.metaDescriptionAr()));
        if (en != null) {
            en.setMetaTitle(changes.set("metaTitleEn", en.getMetaTitle(), request.metaTitleEn()));
            en.setMetaDescription(changes.set("metaDescriptionEn", en.getMetaDescription(), request.metaDescriptionEn()));
        }
        productRepository.saveAndFlush(product);
        changes.audit(auditService, "PRODUCT", id, product.getSlug(), actorId);
        return row("p", id);
    }

    // ---------------------------------------------------------------- categories

    @Transactional(readOnly = true)
    public PageResponse<SeoRowResponse> categories(String query, Filter filter, Pageable pageable) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE c.scope = 'PRODUCT'");
        searchClause(where, args, query, "t.name", "c.slug");
        filterClause(where, filter, false);
        return page(categorySelect(where.toString()), categoryCount(where.toString()), args, pageable,
                (rs, n) -> new SeoRowResponse(rs.getLong("id"), rs.getString("slug"), rs.getString("status"),
                        rs.getString("name"), rs.getString("mta"), rs.getString("mda"), rs.getString("mte"),
                        rs.getString("mde"), rs.getLong("faq"), false));
    }

    @Transactional
    public SeoRowResponse updateCategory(Long id, SeoMetaRequest request, Long actorId) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
        CategoryTranslation ar = category.getTranslations().get("ar");
        if (ar == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The category has no Arabic text to edit");
        }
        CategoryTranslation en = category.getTranslations().get("en");

        Changes changes = new Changes();
        ar.setMetaTitle(changes.set("metaTitleAr", ar.getMetaTitle(), request.metaTitleAr()));
        ar.setMetaDescription(changes.set("metaDescriptionAr", ar.getMetaDescription(), request.metaDescriptionAr()));
        if (en != null) {
            en.setMetaTitle(changes.set("metaTitleEn", en.getMetaTitle(), request.metaTitleEn()));
            en.setMetaDescription(changes.set("metaDescriptionEn", en.getMetaDescription(), request.metaDescriptionEn()));
        }
        categoryRepository.saveAndFlush(category);
        changes.audit(auditService, "CATEGORY", id, category.getSlug(), actorId);
        return row("c", id);
    }

    // --------------------------------------------------------------------- pages

    @Transactional(readOnly = true)
    public List<SeoPageResponse> pages() {
        return pageRepository.findAllByOrderByIdAsc().stream().map(SeoAdminService::toResponse).toList();
    }

    @Transactional
    public SeoPageResponse updatePage(String key, SeoPageRequest request, Long actorId) {
        SeoPage page = pageRepository.findByPageKey(key.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new BusinessException(ErrorCode.SEO_PAGE_NOT_FOUND));
        Changes changes = new Changes();
        page.setTitleAr(changes.set("titleAr", page.getTitleAr(), request.titleAr()));
        page.setDescriptionAr(changes.set("descriptionAr", page.getDescriptionAr(), request.descriptionAr()));
        page.setTitleEn(changes.set("titleEn", page.getTitleEn(), request.titleEn()));
        page.setDescriptionEn(changes.set("descriptionEn", page.getDescriptionEn(), request.descriptionEn()));
        if (page.isNoindex() != request.noindex()) {
            changes.record("noindex", String.valueOf(page.isNoindex()), String.valueOf(request.noindex()));
            page.setNoindex(request.noindex());
        }
        page.setUpdatedBy(actorId);
        pageRepository.save(page);
        changes.audit(auditService, "SEO_PAGE", page.getPageKey(), page.getPageKey(), actorId);
        return toResponse(page);
    }

    /** What a static page puts in its head, in the language asked for (Arabic when the English text is empty). */
    @Transactional(readOnly = true)
    public PublicSeoPageResponse publicPage(String key, String locale) {
        SeoPage page = pageRepository.findByPageKey(key.toUpperCase(Locale.ROOT)).orElse(null);
        if (page == null) {
            return new PublicSeoPageResponse(null, null, false);
        }
        boolean en = "en".equalsIgnoreCase(locale);
        String title = en && hasText(page.getTitleEn()) ? page.getTitleEn() : page.getTitleAr();
        String description = en && hasText(page.getDescriptionEn()) ? page.getDescriptionEn() : page.getDescriptionAr();
        return new PublicSeoPageResponse(blankToNull(title), blankToNull(description), page.isNoindex());
    }

    // ------------------------------------------------------------------ internal

    private SeoRowResponse row(String kind, Long id) {
        boolean product = "p".equals(kind);
        String where = product ? "WHERE p.id = ?" : "WHERE c.id = ?";
        String sql = product ? productSelect(where) : categorySelect(where);
        return jdbc.query(sql, (rs, n) -> new SeoRowResponse(rs.getLong("id"), rs.getString("slug"),
                rs.getString("status"), rs.getString("name"), rs.getString("mta"), rs.getString("mda"),
                rs.getString("mte"), rs.getString("mde"), rs.getLong("faq"),
                product && rs.getInt("dup") == 1), id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(product ? ErrorCode.PRODUCT_NOT_FOUND
                        : ErrorCode.CATEGORY_NOT_FOUND));
    }

    private String productSelect(String where) {
        return "SELECT p.id, p.slug, p.status, t.name, t.meta_title AS mta, t.meta_description AS mda, "
                + "e.meta_title AS mte, e.meta_description AS mde, "
                + "(SELECT COUNT(*) FROM faq_item f WHERE f.scope = 'PRODUCT' AND f.target_id = p.id "
                + "AND f.status = 'PUBLISHED') AS faq, "
                + duplicateExpression("p", "t") + " AS dup "
                + "FROM product p JOIN product_translation t ON t.product_id = p.id AND t.locale = 'ar' "
                + "LEFT JOIN product_translation e ON e.product_id = p.id AND e.locale = 'en' "
                + where + " ORDER BY p.id DESC";
    }

    private String productCount(String where) {
        return "SELECT COUNT(*) FROM product p JOIN product_translation t ON t.product_id = p.id AND t.locale = 'ar' "
                + where;
    }

    private String categorySelect(String where) {
        return "SELECT c.id, c.slug, CASE WHEN c.is_active = 1 THEN 'ACTIVE' ELSE 'INACTIVE' END AS status, "
                + "t.name, t.meta_title AS mta, t.meta_description AS mda, e.meta_title AS mte, "
                + "e.meta_description AS mde, "
                + "(SELECT COUNT(*) FROM faq_item f WHERE f.scope = 'CATEGORY' AND f.target_id = c.id "
                + "AND f.status = 'PUBLISHED') AS faq, 0 AS dup "
                + "FROM category c JOIN category_translation t ON t.category_id = c.id AND t.locale = 'ar' "
                + "LEFT JOIN category_translation e ON e.category_id = c.id AND e.locale = 'en' "
                + where + " ORDER BY c.id";
    }

    private String categoryCount(String where) {
        return "SELECT COUNT(*) FROM category c JOIN category_translation t ON t.category_id = c.id "
                + "AND t.locale = 'ar' " + where;
    }

    /** 1 when another live product has the very same non-empty Arabic meta title. */
    private static String duplicateExpression(String product, String translation) {
        return "CASE WHEN " + translation + ".meta_title IS NOT NULL AND LTRIM(RTRIM(" + translation
                + ".meta_title)) <> N'' AND EXISTS (SELECT 1 FROM product p2 JOIN product_translation t2 "
                + "ON t2.product_id = p2.id AND t2.locale = 'ar' WHERE p2.id <> " + product
                + ".id AND p2.archived_at IS NULL AND t2.meta_title = " + translation + ".meta_title) "
                + "THEN 1 ELSE 0 END";
    }

    private static String noFaq(String scope, String idColumn) {
        return "NOT EXISTS (SELECT 1 FROM faq_item f WHERE f.scope = '" + scope + "' AND f.target_id = " + idColumn
                + " AND f.status = 'PUBLISHED')";
    }

    private static void searchClause(StringBuilder where, List<Object> args, String query, String nameColumn,
                                     String slugColumn) {
        if (query != null && !query.isBlank()) {
            where.append(" AND (").append(nameColumn).append(" LIKE ? OR ").append(slugColumn).append(" LIKE ?)");
            String like = "%" + query.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private static void filterClause(StringBuilder where, Filter filter, boolean product) {
        if (filter == null || filter == Filter.ALL) {
            return;
        }
        switch (filter) {
            case MISSING_TITLE -> where.append(" AND ").append(BLANK_TITLE);
            case MISSING_DESCRIPTION -> where.append(" AND ").append(BLANK_DESCRIPTION);
            case TOO_LONG -> where.append(" AND (LEN(t.meta_title) > ").append(TITLE_LIMIT)
                    .append(" OR LEN(t.meta_description) > ").append(DESCRIPTION_LIMIT).append(")");
            case DUPLICATE -> where.append(product ? " AND " + duplicateExpression("p", "t") + " = 1" : " AND 1 = 0");
            case NO_FAQ -> where.append(" AND ").append(product ? noFaq("PRODUCT", "p.id") : noFaq("CATEGORY", "c.id"));
            default -> { }
        }
    }

    private <T> PageResponse<T> page(String select, String count, List<Object> args, Pageable pageable,
                                     org.springframework.jdbc.core.RowMapper<T> mapper) {
        long total = jdbc.queryForObject(count, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add((long) pageable.getOffset());
        pageArgs.add(pageable.getPageSize());
        List<T> rows = jdbc.query(select + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", mapper, pageArgs.toArray());
        return PageResponse.from(new PageImpl<>(rows, pageable, total));
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static SeoPageResponse toResponse(SeoPage p) {
        return new SeoPageResponse(p.getPageKey(), p.getTitleAr(), p.getDescriptionAr(), p.getTitleEn(),
                p.getDescriptionEn(), p.isNoindex(), p.getUpdatedAt());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return hasText(s) ? s.strip() : null;
    }

    /** The texts that really changed, for the audit log: old and new side by side. */
    private static final class Changes {

        private final StringBuilder from = new StringBuilder();
        private final StringBuilder to = new StringBuilder();

        /** Stores the new value (blank clears it) and remembers the change when there is one. */
        String set(String field, String current, String requested) {
            String next = blankToNull(requested);
            String before = blankToNull(current);
            if (!java.util.Objects.equals(before, next)) {
                record(field, before, next);
            }
            return next;
        }

        void record(String field, String before, String after) {
            from.append(field).append('=').append(before == null ? "∅" : before).append("; ");
            to.append(field).append('=').append(after == null ? "∅" : after).append("; ");
        }

        void audit(AuditService audit, String entityType, Object id, String label, Long actorId) {
            if (from.length() > 0) {
                audit.record(AuditAction.SEO_CHANGED, entityType, id, label, from.toString(), to.toString(), null,
                        actorId);
            }
        }
    }
}
