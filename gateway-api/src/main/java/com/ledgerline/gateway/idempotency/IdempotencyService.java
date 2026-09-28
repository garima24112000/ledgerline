package com.ledgerline.gateway.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.api.ApiException;
import com.ledgerline.gateway.common.Sha256;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key handling. A key row is IN_PROGRESS (a lock with an expiry) until the request
 * that owns it records its response, then COMPLETED (the response is stored for replay).
 */
@Service
public class IdempotencyService {

    enum Decision {
        REPLAY,
        IN_USE,
        TAKE_OVER,
        KEY_REUSED
    }

    private final IdempotencyRepository repository;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, IdempotencyProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Claims {@code key} for a request, in its own short transaction (call it outside any other).
     *
     * @throws ApiException 409 if another request with this key is still running; 422 if the key
     *                      was used for a different request
     */
    @Transactional
    public Claim claim(long merchantId, String key, String requestHash) {
        if (repository.insertInProgress(merchantId, key, requestHash, properties.lockTtl())) {
            return new Claim.Acquired();
        }
        IdempotencyRecord existing = repository.find(merchantId, key)
                .orElseThrow(IdempotencyService::inUse); // deleted in between by a failed request: ask for a retry
        return switch (decide(existing, requestHash)) {
            case REPLAY -> new Claim.Replay(existing.responseCode(), existing.responseBody());
            case TAKE_OVER -> {
                if (repository.tryLock(merchantId, key, properties.lockTtl())) {
                    yield new Claim.Acquired();
                }
                throw inUse(); // another retry took it first
            }
            case IN_USE -> throw inUse();
            case KEY_REUSED -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        };
    }

    /**
     * What to do with a request whose key already exists. The hash is compared first: a key reused
     * for a different request is a client bug, whatever state the first request is in.
     */
    static Decision decide(IdempotencyRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            return Decision.KEY_REUSED;
        }
        if (existing.status() == IdempotencyStatus.COMPLETED) {
            return Decision.REPLAY;
        }
        return existing.lockExpired() ? Decision.TAKE_OVER : Decision.IN_USE;
    }

    /**
     * Fingerprint of a request: the operation (method + path) and the parsed body re-serialized.
     * Hashing the parsed DTO rather than raw bytes means whitespace or field order in the client's
     * JSON doesn't make an identical retry look like a different request.
     */
    public String requestHash(String operation, Object request) {
        return Sha256.hex(operation + "\n" + toJson(request));
    }

    /** Lets a background job (the reconciler) take a released or expired key's lock. */
    @Transactional
    public boolean tryLock(long merchantId, String key) {
        return repository.tryLock(merchantId, key, properties.lockTtl());
    }

    /** Stores the response to replay. Joins the caller's transaction, so it commits with the work. */
    @Transactional
    public void complete(long merchantId, String key, HttpStatus status, Object body) {
        repository.complete(merchantId, key, status.value(), toJson(body));
    }

    @Transactional
    public void release(long merchantId, String key) {
        repository.release(merchantId, key);
    }

    @Transactional
    public void delete(long merchantId, String key) {
        repository.delete(merchantId, key);
    }

    /**
     * Rebuilds a stored response. The body is stored as JSONB, which reorders object keys, so it is
     * read back into the response type and re-serialized; that gives exactly the original bytes.
     */
    public <T> IdempotentResponse<T> replay(Claim.Replay replay, Class<T> type) {
        try {
            T body = objectMapper.readValue(replay.responseBody(), type);
            return new IdempotentResponse<>(HttpStatus.valueOf(replay.responseCode()), body, true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored idempotent response is not a " + type.getSimpleName(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("can't serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private static ApiException inUse() {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_USE",
                "A request with this Idempotency-Key is still in progress; retry later");
    }
}
