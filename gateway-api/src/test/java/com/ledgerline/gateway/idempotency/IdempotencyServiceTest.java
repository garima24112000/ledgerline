package com.ledgerline.gateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ledgerline.gateway.api.ApiException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final long MERCHANT = 7;
    private static final String KEY = "key-1";
    private static final String HASH = "hash-a";
    private static final Duration TTL = Duration.ofSeconds(10);

    @Mock
    private IdempotencyRepository repository;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, new IdempotencyProperties(TTL), JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void newKeyIsAcquired() {
        when(repository.insertInProgress(MERCHANT, KEY, HASH, TTL)).thenReturn(true);

        assertThat(service.claim(MERCHANT, KEY, HASH)).isEqualTo(new Claim.Acquired());
        verify(repository, never()).find(anyLong(), anyString());
    }

    @Test
    void completedKeyWithSameHashIsReplayed() {
        existing(new IdempotencyRecord(HASH, IdempotencyStatus.COMPLETED, 201, "{\"id\":1}", true));

        assertThat(service.claim(MERCHANT, KEY, HASH)).isEqualTo(new Claim.Replay(201, "{\"id\":1}"));
    }

    @Test
    void inProgressKeyWithLiveLockIs409() {
        existing(new IdempotencyRecord(HASH, IdempotencyStatus.IN_PROGRESS, null, null, false));

        assertApiError(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_USE");
        verify(repository, never()).tryLock(anyLong(), anyString(), any());
    }

    @Test
    void completedKeyWithDifferentHashIs422() {
        existing(new IdempotencyRecord("hash-b", IdempotencyStatus.COMPLETED, 201, "{}", true));

        assertApiError(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void differentHashIs422EvenWhileTheFirstRequestIsRunning() {
        existing(new IdempotencyRecord("hash-b", IdempotencyStatus.IN_PROGRESS, null, null, false));

        assertApiError(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void inProgressKeyWithExpiredLockIsTakenOver() {
        existing(new IdempotencyRecord(HASH, IdempotencyStatus.IN_PROGRESS, null, null, true));
        when(repository.tryLock(MERCHANT, KEY, TTL)).thenReturn(true);

        assertThat(service.claim(MERCHANT, KEY, HASH)).isEqualTo(new Claim.Acquired());
    }

    @Test
    void losingTheTakeOverRaceIs409() {
        existing(new IdempotencyRecord(HASH, IdempotencyStatus.IN_PROGRESS, null, null, true));
        when(repository.tryLock(MERCHANT, KEY, TTL)).thenReturn(false);

        assertApiError(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_USE");
    }

    @Test
    void replayRebuildsTheBodyInFieldOrderAndFlagsIt() {
        // JSONB hands keys back in its own order; the replay must not depend on that.
        IdempotentResponse<Body> replay = service.replay(new Claim.Replay(201, "{\"b\": 2, \"a\": \"x\"}"), Body.class);

        assertThat(replay).isEqualTo(new IdempotentResponse<>(HttpStatus.CREATED, new Body("x", 2), true));
        assertThat(replay.toResponseEntity().getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void requestHashDependsOnOperationAndBody() {
        String hash = service.requestHash("POST /v1/payments", new Body("x", 2));

        assertThat(service.requestHash("POST /v1/payments", new Body("x", 2))).isEqualTo(hash).hasSize(64);
        assertThat(service.requestHash("POST /v1/payments", new Body("x", 3))).isNotEqualTo(hash);
        assertThat(service.requestHash("POST /v1/payments/1/refunds", new Body("x", 2))).isNotEqualTo(hash);
    }

    private void existing(IdempotencyRecord record) {
        when(repository.insertInProgress(MERCHANT, KEY, HASH, TTL)).thenReturn(false);
        when(repository.find(MERCHANT, KEY)).thenReturn(Optional.of(record));
    }

    private void assertApiError(HttpStatus status, String code) {
        assertThatThrownBy(() -> service.claim(MERCHANT, KEY, HASH))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }

    record Body(String a, int b) {
    }
}
