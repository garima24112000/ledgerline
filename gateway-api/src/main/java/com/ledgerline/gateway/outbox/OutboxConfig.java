package com.ledgerline.gateway.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class OutboxConfig {

    /** Created at startup by Spring's KafkaAdmin if missing. Partitions bound the dispatcher's parallelism. */
    @Bean
    NewTopic paymentEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .build();
    }
}
