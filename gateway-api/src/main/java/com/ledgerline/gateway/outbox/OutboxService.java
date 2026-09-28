package com.ledgerline.gateway.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an event to publish. {@code MANDATORY}: it must join the caller's transaction, the one
     * that makes the state change, so the change and its event commit or roll back together. Called
     * without a transaction it throws instead of quietly committing the event on its own.
     *
     * @param data the event's {@code data} object, serialized as JSON
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long merchantId, UUID aggregateId, EventType type, Object data) {
        repository.insert(UUID.randomUUID(), merchantId, aggregateId, type, toJson(data));
    }

    private String toJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("can't serialize event data " + data.getClass().getSimpleName(), e);
        }
    }
}
