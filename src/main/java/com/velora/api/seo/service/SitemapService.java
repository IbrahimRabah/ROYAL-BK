package com.velora.api.seo.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code sitemap.xml} and {@code robots.txt}, built from what is really on sale. A product that is a draft or
 * archived, a category with no products and a page marked "hide from search" never appear: the sitemap is a
 * promise that every URL in it is a good page.
 *
 * <p>The storefront (the Angular server) forwards {@code /sitemap.xml} and {@code /robots.txt} here, so the
 * URLs in the file are the storefront's, taken from {@code velora.seo.site-url}.
 */
@Service
public class SitemapService {

    /** Static page keys and their storefront paths. */
    static final Map<String, String> STATIC_PAGES = new LinkedHashMap<>();

    static {
        STATIC_PAGES.put("HOME", "/");
        STATIC_PAGES.put("PRODUCTS", "/products");
        STATIC_PAGES.put("ABOUT", "/about");
        STATIC_PAGES.put("CONTACT", "/contact");
        STATIC_PAGES.put("PORTFOLIO", "/portfolio");
        STATIC_PAGES.put("CUSTOM_REQUEST", "/custom-request");
        STATIC_PAGES.put("FAQ", "/faq");
    }

    /** The sitemap protocol allows 50 000 URLs in one file. */
    private static final int MAX_URLS = 50_000;

    private final JdbcTemplate jdbc;
    private final String siteUrl;

    public SitemapService(JdbcTemplate jdbc, @Value("${velora.seo.site-url:http://localhost:4200}") String siteUrl) {
        this.jdbc = jdbc;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    public String siteUrl() {
        return siteUrl;
    }

    @Transactional(readOnly = true)
    public String sitemap() {
        List<String[]> urls = new ArrayList<>();

        List<String> hidden = jdbc.queryForList("SELECT page_key FROM seo_page WHERE noindex = 1", String.class);
        STATIC_PAGES.forEach((key, path) -> {
            if (!hidden.contains(key)) {
                urls.add(new String[] {path, null});
            }
        });

        jdbc.query("SELECT id FROM category c WHERE c.is_active = 1 AND c.scope = 'PRODUCT' AND EXISTS "
                + "(SELECT 1 FROM product p WHERE p.category_id = c.id AND p.status = 'ACTIVE' "
                + "AND p.archived_at IS NULL) ORDER BY id",
                rs -> {
                    urls.add(new String[] {"/products?categoryId=" + rs.getLong(1), null});
                });
        jdbc.query("SELECT slug, updated_at FROM product WHERE status = 'ACTIVE' AND archived_at IS NULL "
                + "ORDER BY id", rs -> {
            urls.add(new String[] {"/products/" + rs.getString(1), date(rs.getObject(2, OffsetDateTime.class))});
        });
        jdbc.query("SELECT slug, updated_at FROM portfolio_item WHERE published = 1 AND archived_at IS NULL "
                + "ORDER BY id", rs -> {
            urls.add(new String[] {"/portfolio/" + rs.getString(1), date(rs.getObject(2, OffsetDateTime.class))});
        });

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        urls.stream().limit(MAX_URLS).forEach(u -> {
            xml.append("  <url><loc>").append(escape(siteUrl + u[0])).append("</loc>");
            if (u[1] != null) {
                xml.append("<lastmod>").append(u[1]).append("</lastmod>");
            }
            xml.append("</url>\n");
        });
        return xml.append("</urlset>\n").toString();
    }

    /** Keeps crawlers out of the parts of the site that are not public pages. */
    public String robots() {
        return "User-agent: *\n"
                + "Disallow: /admin\n"
                + "Disallow: /me\n"
                + "Disallow: /auth\n"
                + "Disallow: /cart\n"
                + "Disallow: /checkout\n"
                + "Disallow: /wishlist\n"
                + "\n"
                + "Sitemap: " + siteUrl + "/sitemap.xml\n";
    }

    private static String date(OffsetDateTime when) {
        return when == null ? null : DateTimeFormatter.ISO_LOCAL_DATE.format(when.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
