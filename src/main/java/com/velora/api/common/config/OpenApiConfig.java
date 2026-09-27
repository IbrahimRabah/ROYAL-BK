package com.velora.api.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI: http://localhost:8080/swagger-ui.html
 * OpenAPI JSON: http://localhost:8080/v3/api-docs
 *
 * <p>The JSON document is also how the Angular client is generated:
 * <pre>
 * npx openapi-typescript-codegen \
 *     --input http://localhost:8080/v3/api-docs \
 *     --output ./src/app/core/api
 * </pre>
 * That gives typed services for every endpoint, so a contract change becomes a
 * compile error instead of a runtime surprise.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI veloraOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("VELORA E-Commerce API")
                        .version("v1")
                        .description("""
                                Premium watches, wallets and perfumes for the Egyptian market.

                                **Conventions**
                                - All prices are TAX-INCLUSIVE and in EGP
                                - Timestamps are UTC, ISO-8601
                                - Errors follow RFC 7807 with a stable `code` field
                                - Guest carts use the `X-Guest-Token` header
                                """)
                        .contact(new Contact().name("VELORA")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the access token from POST /api/v1/auth/login")));
    }
}
