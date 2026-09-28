package com.ledgerline.dispatcher.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI dispatcherOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledgerline Webhook Dispatcher")
                        .description("Operator endpoints for webhook delivery.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("AdminBasic", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Operator credentials (ADMIN_USERNAME / ADMIN_PASSWORD)")));
    }
}
