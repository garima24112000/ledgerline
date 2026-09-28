package com.ledgerline.gateway.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.gateway.AbstractGatewayIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** The real security filter chain, driven through MockMvc with spring-security-test's httpBasic(). */
class SecurityIT extends AbstractGatewayIT {

    /** Seeded by V3__payments.sql; the raw key is only in README.md. */
    private static final String DEMO_KEY = "sk_test_chaipoint_7Qm2xK9vLp4R";

    @Autowired
    private MockMvc mvc;

    @Test
    void missingApiKeyIs401WithErrorBody() throws Exception {
        mvc.perform(get("/v1/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidApiKeyIs401() throws Exception {
        mvc.perform(get("/v1/payments").header("X-Api-Key", "sk_test_wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void seededDemoKeyAuthenticates() throws Exception {
        mvc.perform(get("/v1/payments").header("X-Api-Key", DEMO_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void merchantCanReadItsOwnPayment() throws Exception {
        TestMerchant merchant = createMerchant();
        UUID paymentId = insertPayment(merchant.id());

        mvc.perform(get("/v1/payments/" + paymentId).header("X-Api-Key", merchant.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId.toString()));
    }

    @Test
    void merchantCannotReadAnotherMerchantsPaymentAndGets404Not403() throws Exception {
        TestMerchant a = createMerchant();
        TestMerchant b = createMerchant();
        UUID paymentOfB = insertPayment(b.id());

        // 404, not 403: a 403 would confirm to A that the payment id exists.
        mvc.perform(get("/v1/payments/" + paymentOfB).header("X-Api-Key", a.apiKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void merchantKeyOnAdminEndpointIs403() throws Exception {
        mvc.perform(get("/admin/ledger/verify").header("X-Api-Key", createMerchant().apiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void adminBasicAuthReachesAdminEndpoint() throws Exception {
        mvc.perform(get("/admin/ledger/verify").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").exists());
    }

    @Test
    void wrongAdminPasswordIs401() throws Exception {
        mvc.perform(get("/admin/ledger/verify").with(httpBasic(ADMIN_USER, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminIsNotAMerchant() throws Exception {
        mvc.perform(get("/v1/payments").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalEndpointNeedsTheServiceToken() throws Exception {
        TestMerchant merchant = createMerchant();
        String path = "/internal/merchants/" + merchant.id() + "/webhook-config";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Service-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Api-Key", merchant.apiKey())).andExpect(status().isForbidden());
        mvc.perform(get(path).header("X-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").value("http://merchant.test/hooks"));
    }

    @Test
    void publicEndpointsNeedNoCredentials() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void unknownPathsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/something-else")).andExpect(status().isUnauthorized());
        mvc.perform(get("/something-else").header("X-Api-Key", createMerchant().apiKey())).andExpect(status().isForbidden());
    }
}
