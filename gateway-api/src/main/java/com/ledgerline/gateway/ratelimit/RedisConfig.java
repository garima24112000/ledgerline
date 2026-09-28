package com.ledgerline.gateway.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    /**
     * While the connection to Redis is down, Lettuce by default queues commands and waits for a
     * reconnect, so every request would wait the full command timeout before failing open.
     * REJECT_COMMANDS fails them at once instead. The other options repeat Spring Boot's defaults,
     * because setting {@code clientOptions} replaces them.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhileRedisIsDown(RedisProperties redis) {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(redis.getConnectTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }
}
