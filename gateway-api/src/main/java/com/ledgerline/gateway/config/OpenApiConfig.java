package com.ledgerline.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI ledgerlineOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Ledgerline Gateway API")
                .description("Payments API for small merchants")
                .version("v1"));
    }
}
