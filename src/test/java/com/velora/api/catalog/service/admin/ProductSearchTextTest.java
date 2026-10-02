package com.velora.api.catalog.service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code search_text} is indexed by {@code ix_prod_search}, whose entries SQL Server caps at
 * 1700 bytes: 849 Arabic characters fit, 850 fail. The cap in
 * {@code ProductAdminService.buildSearchText} is what keeps a product save from hitting that,
 * so the cap has to be below the limit - and has to actually be applied.
 */
@SpringBootTest
class ProductSearchTextTest {

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("The cap is below what the index accepts (849 characters), with a margin")
    void capIsBelowTheIndexLimit() {
        assertThat(ProductAdminService.MAX_SEARCH_TEXT_LENGTH).isEqualTo(800);
        assertThat(ProductAdminService.MAX_SEARCH_TEXT_LENGTH).isLessThan(849);
    }

    @Test
    @DisplayName("A long name and description are cut to the cap")
    void longTextIsTruncated() {
        String longName = "ا".repeat(600);
        String longDescription = "ب".repeat(900);

        String searchText = ProductAdminService.buildSearchText(longName, longDescription);

        assertThat(searchText).hasSize(ProductAdminService.MAX_SEARCH_TEXT_LENGTH);
        assertThat(searchText).startsWith("ا".repeat(600));
    }

    @Test
    @DisplayName("Text within the cap is stored whole — including the largest the API allows: 255 + 500")
    void shortTextIsNotTouched() {
        String name = "ج".repeat(255);
        String description = "د".repeat(500);

        String searchText = ProductAdminService.buildSearchText(name, description);

        assertThat(searchText).hasSize(255 + 1 + 500);
        assertThat(searchText).isEqualTo(name + " " + description);
    }

    @Test
    @DisplayName("A product with no short description still gets a search text")
    void missingDescription() {
        assertThat(ProductAdminService.buildSearchText("ساعة", null)).isNotBlank();
    }

    @Test
    @DisplayName("What the cap produces really inserts into the indexed column, in the real schema")
    void cappedTextFitsTheIndex() {
        String capped = ProductAdminService.buildSearchText("ش".repeat(255), "ص".repeat(500) + "ض".repeat(900));
        assertThat(capped).hasSize(ProductAdminService.MAX_SEARCH_TEXT_LENGTH);

        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO category (slug) VALUES ('search-text-probe-cat')");
            Long categoryId = jdbc.queryForObject(
                    "SELECT id FROM category WHERE slug = 'search-text-probe-cat'", Long.class);
            jdbc.update("INSERT INTO product (category_id, slug) VALUES (?, 'search-text-probe-product')",
                    categoryId);
            Long productId = jdbc.queryForObject(
                    "SELECT id FROM product WHERE slug = 'search-text-probe-product'", Long.class);

            // Would throw error 1946 if the capped text did not fit ix_prod_search.
            jdbc.update("INSERT INTO product_translation (product_id, locale, name, search_text) "
                    + "VALUES (?, 'ar', N'x', ?)", productId, capped);

            Integer stored = jdbc.queryForObject(
                    "SELECT LEN(search_text) FROM product_translation WHERE product_id = ?",
                    Integer.class, productId);
            assertThat(stored).isEqualTo(ProductAdminService.MAX_SEARCH_TEXT_LENGTH);

            status.setRollbackOnly();   // nothing is left behind
        });
    }
}
