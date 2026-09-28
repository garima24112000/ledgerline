package com.ledgerline.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ledgerline.gateway.merchant.Merchant;
import com.ledgerline.gateway.merchant.MerchantRepository;
import com.ledgerline.gateway.merchant.RateLimitTier;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationProviderTest {

    @Mock
    private MerchantRepository merchantRepository;

    @InjectMocks
    private ApiKeyAuthenticationProvider provider;

    @Test
    void knownKeyAuthenticatesTheMerchantByItsHash() {
        Merchant merchant = new Merchant("Chai Point", "unused", "http://x", "s", RateLimitTier.STANDARD);
        ReflectionTestUtils.setField(merchant, "id", 42L);
        // Looked up by hash: the raw key is never stored or queried.
        when(merchantRepository.findByApiKeyHash(ApiKeys.hash("sk_test_abc"))).thenReturn(Optional.of(merchant));

        Authentication result = provider.authenticate(ApiKeyAuthenticationToken.unauthenticated("sk_test_abc"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo(new MerchantPrincipal(42L, "Chai Point"));
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_MERCHANT");
    }

    @Test
    void unknownKeyIsRejected() {
        when(merchantRepository.findByApiKeyHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.authenticate(ApiKeyAuthenticationToken.unauthenticated("nope")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void hashIsLowercaseHexSha256() {
        assertThat(ApiKeys.hash("sk_test_chaipoint_7Qm2xK9vLp4R"))
                .isEqualTo("6474ca4bfd9754170b61d78c24e94c24af28d55e3ae22acd6d1e597ee8f8852d");
    }
}
