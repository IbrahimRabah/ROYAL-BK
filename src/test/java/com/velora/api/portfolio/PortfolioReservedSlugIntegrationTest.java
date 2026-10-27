package com.velora.api.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.portfolio.service.PortfolioAdminService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A portfolio item whose slug equals a fixed path under /portfolio (today: "categories") would be
 * answered by that path instead of the item, so it could never be opened. Such a slug is refused
 * with a reason, and a path added to the controller later must be added to the reserved set - this
 * class fails until it is.
 */
@SpringBootTest
class PortfolioReservedSlugIntegrationTest {

    private static final long ACTOR = 999_004L;
    private static final String ADMIN = "/api/v1/admin/portfolio";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String tag;
    private String generatedSlug;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ?", ACTOR);
        jdbc.update("DELETE FROM portfolio_item WHERE slug LIKE ?", "%" + tag + "%");
        if (generatedSlug != null) {
            jdbc.update("DELETE FROM portfolio_item WHERE slug = ?", generatedSlug);
        }
    }

    @Test
    @DisplayName("Every fixed path under /portfolio is a reserved slug, so no item can be shadowed")
    void everyFixedPathIsReserved() {
        Set<String> fixedSegments = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> patterns(info).stream())
                .filter(p -> p.startsWith("/api/v1/portfolio/"))
                .map(p -> p.substring("/api/v1/portfolio/".length()))
                .filter(rest -> !rest.contains("/") && !rest.contains("{"))
                .collect(Collectors.toSet());

        assertThat(fixedSegments).as("fixed paths directly under /portfolio").contains("categories");
        assertThat(PortfolioAdminService.RESERVED_SLUGS)
                .as("add the new path to PortfolioAdminService.RESERVED_SLUGS")
                .containsAll(fixedSegments);
    }

    @Test
    @DisplayName("Creating an item with the slug 'categories' is refused with SLUG_RESERVED and a reason")
    void createWithReservedSlug() throws Exception {
        mvc.perform(post(ADMIN).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"أبواب " + tag + "\",\"slug\":\"categories\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SLUG_RESERVED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Choose another slug")));

        // Spelled differently, it normalises to the same slug and is refused the same way.
        mvc.perform(post(ADMIN).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"أبواب " + tag + "\",\"slug\":\"  Categories \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SLUG_RESERVED"));
    }

    @Test
    @DisplayName("Renaming an item's slug to 'categories' is refused; its own slug stays as it was")
    void updateToReservedSlug() throws Exception {
        String slug = "reserved-update-" + tag;
        long id = create(slug);

        mvc.perform(put(ADMIN + "/{id}", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"أبواب\",\"slug\":\"categories\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SLUG_RESERVED"));

        mvc.perform(get(ADMIN + "/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug));
    }

    @Test
    @DisplayName("A slug generated from the title that lands on 'categories' takes the next suffix instead of failing")
    void generatedSlugSkipsTheReservedWord() throws Exception {
        String created = mvc.perform(post(ADMIN).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"Categories\",\"titleEn\":\"" + tag + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String slug = JsonPath.read(created, "$.slug");
        generatedSlug = slug;
        assertThat(slug).startsWith("categories-").isNotEqualTo("categories");
    }

    @Test
    @DisplayName("The reserved path still answers with the category list")
    void theReservedPathIsTheCategoryList() throws Exception {
        mvc.perform(get("/api/v1/portfolio/categories")).andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private long create(String slug) throws Exception {
        String item = mvc.perform(post(ADMIN).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleAr\":\"عمل\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(item, "$.id")).longValue();
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }

    private static RequestPostProcessor admin() {
        UserPrincipal principal = UserPrincipal.of(ACTOR, "slug@example.com", null, List.of("ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
    }
}
