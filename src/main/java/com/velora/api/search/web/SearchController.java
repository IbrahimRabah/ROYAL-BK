package com.velora.api.search.web;

import com.velora.api.catalog.web.LocaleResolver;
import com.velora.api.search.dto.SearchSuggestionsResponse;
import com.velora.api.search.service.SearchSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Search", description = "Type-ahead for the storefront search box")
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchSuggestionService suggestionService;

    public SearchController(SearchSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @Operation(summary = "Search suggestions",
            description = """
                    Up to 6 products and 4 categories matching what the customer has typed so far.

                    Products match exactly as `GET /products?q=` does - ACTIVE and not archived,
                    normalized name and short description in either language - so a suggestion
                    never disagrees with the results page it leads to. Categories are PRODUCT
                    categories only: a portfolio category never appears here.

                    Arabic spellings are folded together (اثاث finds أثاث, كرسى finds كرسي,
                    طاوله finds طاولة, a tatweel is ignored). Fewer than 2 letters after that
                    folding, or a missing `q`, is `200` with two empty lists, never an error.
                    No match is `200` with two empty lists, never `404`.

                    Called on every keystroke: debounce it (about 250 ms) and drop the response
                    of a request a newer one has replaced.
                    """,
            security = {})
    @GetMapping("/suggestions")
    public SearchSuggestionsResponse suggestions(
            @Parameter(description = "What the customer typed. At most 60 characters are used")
            @RequestParam(required = false) String q,
            HttpServletRequest request) {
        return suggestionService.suggest(q, LocaleResolver.resolve(request));
    }
}
