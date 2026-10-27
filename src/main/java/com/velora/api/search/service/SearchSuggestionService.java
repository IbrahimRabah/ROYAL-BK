package com.velora.api.search.service;

import com.velora.api.catalog.domain.Category;
import com.velora.api.catalog.domain.CategoryScope;
import com.velora.api.catalog.domain.Product;
import com.velora.api.catalog.domain.ProductImage;
import com.velora.api.catalog.repository.CategoryRepository;
import com.velora.api.catalog.repository.ProductRepository;
import com.velora.api.catalog.spec.ProductSpecifications;
import com.velora.api.common.storage.StorageService;
import com.velora.api.common.util.ArabicNormalizer;
import com.velora.api.search.dto.SearchSuggestionsResponse;
import com.velora.api.search.dto.SearchSuggestionsResponse.CategorySuggestion;
import com.velora.api.search.dto.SearchSuggestionsResponse.ProductSuggestion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Type-ahead for the storefront search box.
 *
 * <h2>Why Arabic matching is done in Java and not by a SQL collation</h2>
 * Tried on the real database before this was written: no accent-insensitive Arabic collation folds
 * the alef forms (ا أ إ آ) or taa marbuta (ة ه), and only the legacy {@code Arabic_CI_AI} folds
 * ى/ي (the {@code Arabic_100_*} ones do not). So {@code اثاث} never found {@code أثاث} and
 * {@code طاوله} never found {@code طاولة}, whichever collation was used. {@link ArabicNormalizer}
 * folds all of them (and tashkeel, tatweel, ؤ ئ), and it is the SAME function that writes
 * {@code product_translation.search_text} and builds the query for {@code GET /products?q=}.
 * <b>Do not "simplify" this into a SQL comparison</b> - a collation cannot do it, and a second
 * normalizer written in SQL would drift from this one until search disagreed with itself.
 *
 * <h2>Products</h2>
 * The very same rule as the results page: {@link ProductSpecifications#isVisible()} (ACTIVE, not
 * archived) and {@link ProductSpecifications#matches} (normalized name + short description, in both
 * languages, or the slug). A suggestion list that disagrees with the page it leads to is worse than
 * none, so this deliberately reuses those two specifications instead of writing its own.
 * The count query a {@code Page} would run is skipped: only the first few rows are read.
 *
 * <h2>Categories - loaded and compared in memory, on every request</h2>
 * {@code category_translation} has no search column, and adding one would oblige every future
 * category save to rebuild it - the kind of duty that is forgotten once and then returns wrong
 * results for months. Instead the active PRODUCT categories are read (one query, translations
 * fetched with them) and each name is normalized and compared here. That is free for the few dozen
 * categories a shop has (26 today); it stops being free around a few thousand, when the per-request
 * read and the two normalizations per category start to show - then cache the normalized names, or
 * give the table a {@code search_text}.
 *
 * <h2>Cost</h2>
 * The product match is a substring scan ({@code LIKE '%x%'}) of {@code search_text}, per keystroke.
 * Measured on a dev machine: about 30 ms at 10,000 products, 130 ms at 50,000, 310 ms at 100,000.
 * Fine for a furniture catalogue; at ~50,000 products it needs a real search engine. Hence the
 * 2-character minimum and the result caps.
 */
@Service
@Transactional(readOnly = true)
public class SearchSuggestionService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 60;
    static final int MAX_PRODUCTS = 6;
    static final int MAX_CATEGORIES = 4;
    /** Same guard the tree uses against a cycle introduced by a bad parent_id. */
    private static final int MAX_DEPTH = 5;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final StorageService storageService;

    public SearchSuggestionService(ProductRepository productRepository,
                                   CategoryRepository categoryRepository,
                                   StorageService storageService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.storageService = storageService;
    }

    public SearchSuggestionsResponse suggest(String rawQuery, String locale) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH);
        }

        // Measured after normalizing: "ً" or "!!" is not a query, and a lone letter is too short.
        String normalized = ArabicNormalizer.normalize(query);
        if (normalized == null || normalized.length() < MIN_QUERY_LENGTH) {
            return SearchSuggestionsResponse.EMPTY;
        }

        return new SearchSuggestionsResponse(
                products(query, locale),
                categories(normalized, locale));
    }

    // ----------------------------------------------------------------------- products

    private List<ProductSuggestion> products(String query, String locale) {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.isVisible())
                .and(ProductSpecifications.matches(query, locale));

        List<Product> found = productRepository.findBy(spec, q -> q
                .sortBy(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(MAX_PRODUCTS)
                .all());

        return found.stream().map(p -> toSuggestion(p, locale)).toList();
    }

    private ProductSuggestion toSuggestion(Product product, String locale) {
        ProductImage main = product.mainImage();
        return new ProductSuggestion(
                product.nameFor(locale),
                product.getSlug(),
                main == null ? null : storageService.urlFor(main.getUrl()),
                product.getMinPrice());
    }

    // --------------------------------------------------------------------- categories

    private List<CategorySuggestion> categories(String normalizedQuery, String locale) {
        // "a b" matches the way the product LIKE '%a%b%' does: the words, in that order.
        String[] words = normalizedQuery.split(" ");

        List<Category> all = categoryRepository
                .findByScopeAndActiveTrueOrderByDisplayOrderAscIdAsc(CategoryScope.PRODUCT);
        Set<Long> activeIds = new HashSet<>();
        all.forEach(c -> activeIds.add(c.getId()));

        List<CategorySuggestion> out = new ArrayList<>();
        for (Category category : all) {
            if (out.size() == MAX_CATEGORIES) {
                break;
            }
            // Only what the shop menu can reach: a category under an inactive parent is not in it.
            if (reachableFromMenu(category, activeIds) && matches(category, words)) {
                out.add(new CategorySuggestion(category.nameFor(locale), category.getSlug()));
            }
        }
        return out;
    }

    private static boolean reachableFromMenu(Category category, Set<Long> activeIds) {
        Category parent = category.getParent();
        for (int depth = 0; parent != null; depth++) {
            if (depth >= MAX_DEPTH || !activeIds.contains(parent.getId())) {
                return false;
            }
            parent = parent.getParent();
        }
        return true;
    }

    /** The name in either language, or the Latin slug - like the product match. */
    private static boolean matches(Category category, String[] words) {
        if (containsInOrder(ArabicNormalizer.normalize(category.getSlug()), words)) {
            return true;
        }
        return category.getTranslations().values().stream()
                .anyMatch(t -> containsInOrder(ArabicNormalizer.normalize(t.getName()), words));
    }

    private static boolean containsInOrder(String text, String[] words) {
        if (text == null) {
            return false;
        }
        int from = 0;
        for (String word : words) {
            int at = text.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            from = at + word.length();
        }
        return true;
    }
}
