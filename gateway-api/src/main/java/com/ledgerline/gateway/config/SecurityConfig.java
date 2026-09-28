package com.ledgerline.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.merchant.MerchantRepository;
import com.ledgerline.gateway.ratelimit.RateLimitFilter;
import com.ledgerline.gateway.ratelimit.RateLimitProperties;
import com.ledgerline.gateway.ratelimit.RedisRateLimiter;
import com.ledgerline.gateway.security.ApiKeyAuthenticationFilter;
import com.ledgerline.gateway.security.ApiKeyAuthenticationProvider;
import com.ledgerline.gateway.security.AuthProperties;
import com.ledgerline.gateway.security.JsonSecurityErrorHandler;
import com.ledgerline.gateway.security.ServiceTokenAuthenticationFilter;
import com.ledgerline.gateway.security.ServiceTokenAuthenticationProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * One stateless filter chain with three ways to authenticate:
 * <ul>
 *   <li>{@code X-Api-Key} → ROLE_MERCHANT (merchants calling /v1/**)</li>
 *   <li>{@code X-Service-Token} → ROLE_SERVICE (webhook-dispatcher calling /internal/**)</li>
 *   <li>HTTP Basic → ROLE_ADMIN (operators calling /admin/**)</li>
 * </ul>
 * It is a single chain on purpose: a merchant key sent to /admin/** is still recognised as a
 * merchant, so the answer is 403 ("known, not allowed"), not 401.
 */
@Configuration
public class SecurityConfig {

    /**
     * Built by hand from all three providers. If a custom {@code AuthenticationProvider} were exposed
     * as a bean instead, Spring Boot would stop wiring a {@code UserDetailsService} into its default
     * manager, and HTTP Basic would quietly stop working.
     */
    @Bean
    AuthenticationManager authenticationManager(MerchantRepository merchantRepository, AuthProperties auth) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        InMemoryUserDetailsManager admins = new InMemoryUserDetailsManager(User.withUsername(auth.adminUsername())
                .password(encoder.encode(auth.adminPassword()))
                .roles("ADMIN")
                .build());
        DaoAuthenticationProvider adminProvider = new DaoAuthenticationProvider();
        adminProvider.setUserDetailsService(admins);
        adminProvider.setPasswordEncoder(encoder);

        return new ProviderManager(
                new ApiKeyAuthenticationProvider(merchantRepository),
                new ServiceTokenAuthenticationProvider(auth.serviceToken()),
                adminProvider);
    }

    /**
     * The rate-limit filter is created here, not declared as a bean: Spring Boot registers every
     * {@code Filter} bean as a servlet filter too, so it would run twice, once before security.
     * It goes after {@code AuthorizationFilter}, so bad keys (401) and forbidden paths (403) never
     * use a merchant's tokens.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager authenticationManager,
                                            JsonSecurityErrorHandler errors, RedisRateLimiter rateLimiter,
                                            RateLimitProperties rateLimits, MeterRegistry meterRegistry,
                                            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable) // no cookies or sessions, so nothing for CSRF to ride on
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(authenticationManager)
                .httpBasic(basic -> basic.authenticationEntryPoint(errors.entryPoint()))
                .addFilterBefore(new ApiKeyAuthenticationFilter(authenticationManager, errors.entryPoint()),
                        BasicAuthenticationFilter.class)
                .addFilterBefore(new ServiceTokenAuthenticationFilter(authenticationManager, errors.entryPoint()),
                        BasicAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimits, meterRegistry, objectMapper),
                        AuthorizationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors.entryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers("/v1/**").hasRole("MERCHANT")
                        .anyRequest().denyAll())
                .build();
    }
}
