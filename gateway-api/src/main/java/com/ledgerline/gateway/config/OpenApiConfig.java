package com.ledgerline.gateway.config;

import com.ledgerline.gateway.security.ApiKeyAuthenticationFilter;
import com.ledgerline.gateway.security.ServiceTokenAuthenticationFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /**
     * The security schemes let Swagger UI's "Authorize" button send credentials, so every endpoint
     * can be tried from the browser. Controllers pick one with {@code @SecurityRequirement(name = ...)}.
     */
    @Bean
    OpenAPI ledgerlineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledgerline Gateway API")
                        .description("Payments API for small merchants. Amounts are integers in minor units (paise). "
                                + "Every error has the shape {\"error\": {\"code\", \"message\"}}.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(ApiKeyAuthenticationFilter.HEADER)
                                .description("Merchant API key, e.g. a demo key from README.md"))
                        .addSecuritySchemes("AdminBasic", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Operator credentials (ADMIN_USERNAME / ADMIN_PASSWORD)"))
                        .addSecuritySchemes("ServiceToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(ServiceTokenAuthenticationFilter.HEADER)
                                .description("Shared secret for internal services (INTERNAL_SERVICE_TOKEN)")));
    }
}
