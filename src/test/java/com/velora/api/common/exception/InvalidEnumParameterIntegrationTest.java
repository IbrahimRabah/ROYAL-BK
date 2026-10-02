package com.velora.api.common.exception;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * An enum value the client got wrong is a 400, never a 500.
 *
 * <p>Goes through the real filter chain and the real controllers: the three places
 * that parse an enum are three different mechanisms (a bound record property, an
 * {@code EnumParam} call in a controller, an {@code EnumParam} call in a service), and
 * a unit test of the handler alone cannot tell whether each one actually reaches it.
 */
@SpringBootTest
class InvalidEnumParameterIntegrationTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("GET /products?fulfillmentType=BANANA is a 400, not a 500")
    void unknownFulfillmentTypeFilter() throws Exception {
        mvc.perform(get("/api/v1/products").param("fulfillmentType", "BANANA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /products?categoryId=abc is a 400 too — same binding path as the enum filter")
    void nonNumericCategoryFilter() throws Exception {
        mvc.perform(get("/api/v1/products").param("categoryId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH /admin/orders/{id}/payment-status with a bad status is a 400")
    void unknownPaymentStatus() throws Exception {
        mvc.perform(patch("/api/v1/admin/orders/1/payment-status")
                        .param("status", "BANANA")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /admin/audit with a bad action is a 400")
    void unknownAuditAction() throws Exception {
        mvc.perform(get("/api/v1/admin/audit")
                        .param("action", "BANANA")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
}
