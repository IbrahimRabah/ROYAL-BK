package com.velora.api.common.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.api.identity.security.JwtService;
import com.velora.api.identity.security.UserPrincipal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 401 means "not authenticated" and 403 means "authenticated but not allowed". The client
 * refreshes its token on 401 and must never do so for a permission error, so the two must not
 * be mixed.
 */
@SpringBootTest
class AuthenticationEntryPointIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Value("${velora.jwt.secret}") private String jwtSecret;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    @DisplayName("Anonymous on a protected route is 401 UNAUTHORIZED, in the standard error shape")
    void anonymousIs401() throws Exception {
        for (String path : List.of("/api/v1/admin/orders", "/api/v1/me/orders",
                "/api/v1/me/addresses", "/api/v1/auth/logout-all")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.instance").value(path));
        }
        mvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A signed-in customer on an admin route is 403 FORBIDDEN, never 401")
    void customerOnAdminIs403() throws Exception {
        UserPrincipal customer = UserPrincipal.of(999_002L, "c@example.com", null, List.of("CUSTOMER"));
        mvc.perform(get("/api/v1/admin/orders").with(authentication(
                        new UsernamePasswordAuthenticationToken(customer, null, customer.authorities()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("An expired or malformed token is 401 on a protected route")
    void expiredTokenIs401() throws Exception {
        // Negative lifetime: the token is born already expired, signed with the real key.
        String expired = new JwtService(jwtSecret, -5, 30)
                .generateAccessToken(1L, "a@example.com", null, Set.of("ADMIN"));

        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Guest flows are untouched: public routes stay open, even with a stale token")
    void guestFlowsStillOpen() throws Exception {
        String expired = new JwtService(jwtSecret, -5, 30)
                .generateAccessToken(1L, "a@example.com", null, Set.of("CUSTOMER"));

        mvc.perform(get("/api/v1/geo/governorates")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/geo/governorates").header("Authorization", "Bearer " + expired))
                .andExpect(status().isOk());

        String token = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post("/api/v1/cart/guest-token")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.guestToken");
        mvc.perform(get("/api/v1/cart").header("X-Guest-Token", token)).andExpect(status().isOk());
        // Whatever business answer these give, it is never an authentication one.
        int quote = mvc.perform(post("/api/v1/shipping/quote").contentType(MediaType.APPLICATION_JSON)
                .header("X-Guest-Token", token).content("{\"governorateId\":1}"))
                .andReturn().getResponse().getStatus();
        int order = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .header("X-Guest-Token", token).content("{}")).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(List.of(quote, order)).doesNotContain(401, 403);
    }
}
