package com.ledgerline.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Every listener container (main topic, retry topics, DLT) must poll on platform threads: Kafka's
 * consumer-group code pins virtual threads during rebalances (see DispatcherConfig).
 */
class ConsumerThreadsIT extends AbstractDispatcherIT {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Test
    void everyListenerContainerRunsItsConsumersOnPlatformThreads() throws Exception {
        Collection<MessageListenerContainer> containers = registry.getAllListenerContainers();
        assertThat(containers).hasSize(4); // payment.events, 2 retry topics (3 attempts in tests), DLT

        for (MessageListenerContainer container : containers) {
            AsyncTaskExecutor executor = container.getContainerProperties().getListenerTaskExecutor();
            assertThat(executor).as(container.getListenerId()).isNotNull();

            boolean virtual = CompletableFuture.supplyAsync(() -> Thread.currentThread().isVirtual(), executor).get();
            assertThat(virtual).as(container.getListenerId()).isFalse();
        }
    }
}
