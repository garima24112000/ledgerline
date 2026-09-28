package com.ledgerline.dispatcher.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DispatcherConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
