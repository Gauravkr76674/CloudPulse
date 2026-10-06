package com.cloudops.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at http://localhost:8080/swagger-ui.html with a "Authorize" button
 * that accepts the JWT returned by POST /api/auth/login.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI cloudOpsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Multi-Model AI Agent for Intelligent Cloud Operations")
                        .description("Monitors AWS resources, opens incidents, asks an AI layer for "
                                + "diagnosis and recommendations, and executes only pre-approved safe actions.")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .name(SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
