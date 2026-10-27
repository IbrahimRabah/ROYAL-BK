package com.velora.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.identity.security.UserPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Responses leave out fields that are null (the application's JSON rule, set in the main
 * application.yml). The test profile used to replace that file without the setting, so a test that
 * said "this field is absent" was reading a response that returned it as null: such a test cannot
 * fail, and quietly blesses the bug it is meant to catch.
 *
 * <p>This reads the RAW body of a real response, because JsonPath's doesNotExist() also passes for
 * a field that is present with a null value.
 */
@SpringBootTest
class NullStrippingParityIntegrationTest {

    private static final long ACTOR = 999_005L;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String slug;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        slug = "null-probe-" + UUID.randomUUID().toString().substring(0, 8);
        UserPrincipal admin = UserPrincipal.of(ACTOR, "nulls@example.com", null, List.of("ADMIN"));
        mvc.perform(post("/api/v1/admin/categories")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                admin, null, admin.authorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"" + slug + "\",\"scope\":\"PORTFOLIO\",\"translations\":"
                                + "[{\"locale\":\"ar\",\"name\":\"اختبار\"}]}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM category_translation WHERE category_id IN "
                + "(SELECT id FROM category WHERE slug = ?)", slug);
        jdbc.update("DELETE FROM category WHERE slug = ?", slug);
    }

    @Test
    @DisplayName("A field with no value is absent from the response body, not present as null")
    void nullFieldsAreLeftOut() throws Exception {
        // This category has no image, so its imageUrl is null in the DTO.
        String body = mvc.perform(get("/api/v1/portfolio/categories")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"slug\":\"" + slug + "\"");
        assertThat(body).as("imageUrl is null for this category, so it must not be in the body")
                .doesNotContain("imageUrl").doesNotContain(":null");
    }
}
