package com.ledgerline.dispatcher.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

@Configuration
class DispatcherConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Kafka consumers run on platform threads, even though spring.threads.virtual.enabled puts them on
     * virtual threads by default. The Kafka client joins and leaves the consumer group inside
     * {@code synchronized} methods (AbstractCoordinator), so during a rebalance every consumer pins
     * its carrier. With a 1-CPU container limit there is one carrier: 18 pinned consumers (3 per topic,
     * 6 topics) starved Tomcat, health probes timed out, and Kubernetes restarted the pod in a loop.
     * The consumers are long-lived pollers, so virtual threads gave them nothing anyway. Webhook
     * HTTP calls and requests still use virtual threads.
     */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> platformThreadConsumers() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("kafka-consumer-"); // platform threads
        return container -> container.getContainerProperties().setListenerTaskExecutor(executor);
    }
}
