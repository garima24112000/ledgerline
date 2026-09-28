package com.ledgerline.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.gateway.merchant.RateLimitTier;
import com.ledgerline.gateway.ratelimit.RateLimitProperties.TierLimit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class RateLimitPropertiesTest {

    @Test
    void applicationYmlHasTheAgreedLimitsPerTier() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        RateLimitProperties properties = new Binder(ConfigurationPropertySources.get(environment),
                new PropertySourcesPlaceholdersResolver(environment)) // resolves ${RATE_LIMIT_ENABLED:true}
                .bindOrCreate("gateway.rate-limit", RateLimitProperties.class);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.limitFor(RateLimitTier.FREE)).isEqualTo(new TierLimit(20, 40));
        assertThat(properties.limitFor(RateLimitTier.PRO)).isEqualTo(new TierLimit(200, 400));
    }

    @Test
    void everyTierNeedsALimit() {
        assertThatThrownBy(() -> new RateLimitProperties(true, Map.of(RateLimitTier.FREE, new TierLimit(1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRO");
    }

    @Test
    void rateAndBurstMustBePositive() {
        assertThatThrownBy(() -> new TierLimit(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TierLimit(0, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
