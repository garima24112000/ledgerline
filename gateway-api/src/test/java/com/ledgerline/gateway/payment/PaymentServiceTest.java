package com.ledgerline.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ledgerline.gateway.api.ApiException;
import com.ledgerline.gateway.idempotency.Claim;
import com.ledgerline.gateway.idempotency.IdempotencyService;
import com.ledgerline.gateway.idempotency.IdempotentResponse;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final long MERCHANT = 7;
    private static final String KEY = "key-1";
    private static final CreatePaymentRequest REQUEST = new CreatePaymentRequest(49_900L, "INR", "tok_visa", "order-1");

    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentQueryRepository paymentQueryRepository;
    @Mock
    private PaymentTransitions transitions;
    @Mock
    private RefundService refundService;
    @Mock
    private BankClient bankClient;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private PaymentService paymentService;

    private final Payment pending = Payment.pending(MERCHANT, KEY, 49_900, "INR", "tok_visa", "order-1");

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(idempotencyService, paymentRepository, paymentQueryRepository, transitions,
                refundService, bankClient, meterRegistry);
        lenient().when(idempotencyService.requestHash(anyString(), any())).thenReturn("hash");
        lenient().when(idempotencyService.claim(MERCHANT, KEY, "hash")).thenReturn(new Claim.Acquired());
        lenient().when(paymentRepository.findByMerchantIdAndIdempotencyKey(MERCHANT, KEY)).thenReturn(Optional.empty());
        lenient().when(transitions.createPending(MERCHANT, KEY, REQUEST)).thenReturn(pending);
    }

    @Test
    void approvedChargeIsRecorded() {
        BankResult approved = BankResult.approved("bnk_1");
        IdempotentResponse<PaymentResponse> captured = response(HttpStatus.CREATED);
        when(bankClient.charge(pending.getId(), 49_900, "tok_visa")).thenReturn(approved);
        when(transitions.recordBankResult(pending.getId(), approved)).thenReturn(captured);

        assertThat(paymentService.create(MERCHANT, KEY, REQUEST)).isSameAs(captured);
        verify(transitions, never()).markUnknown(any());
    }

    @Test
    void declinedChargeIsRecordedAsTheBankAnswered() {
        BankResult declined = BankResult.declined("insufficient_funds", "bnk_2");
        when(bankClient.charge(pending.getId(), 49_900, "tok_visa")).thenReturn(declined);
        when(transitions.recordBankResult(pending.getId(), declined)).thenReturn(response(HttpStatus.CREATED));

        paymentService.create(MERCHANT, KEY, REQUEST);

        verify(transitions).recordBankResult(pending.getId(), declined);
    }

    @Test
    void bankTimeoutMarksThePaymentUnknownNotFailed() {
        when(bankClient.charge(pending.getId(), 49_900, "tok_visa"))
                .thenThrow(new BankUnavailableException("timeout", new SocketTimeoutException()));
        IdempotentResponse<PaymentResponse> unknown = response(HttpStatus.ACCEPTED);
        when(transitions.markUnknown(pending.getId())).thenReturn(unknown);

        assertThat(paymentService.create(MERCHANT, KEY, REQUEST)).isSameAs(unknown);
        verify(transitions, never()).recordBankResult(any(), any());
    }

    @Test
    void replayNeverReachesTheBank() {
        Claim.Replay replay = new Claim.Replay(201, "{}");
        IdempotentResponse<PaymentResponse> replayed = new IdempotentResponse<>(HttpStatus.CREATED, null, true);
        when(idempotencyService.claim(MERCHANT, KEY, "hash")).thenReturn(replay);
        when(idempotencyService.replay(replay, PaymentResponse.class)).thenReturn(replayed);

        assertThat(paymentService.create(MERCHANT, KEY, REQUEST)).isSameAs(replayed);
        verifyNoInteractions(bankClient, transitions);
    }

    @Test
    void retryAfterTimeoutChargesTheSamePaymentAgain() {
        Payment unknown = Payment.pending(MERCHANT, KEY, 49_900, "INR", "tok_visa", "order-1");
        unknown.markUnknown();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(MERCHANT, KEY)).thenReturn(Optional.of(unknown));
        when(bankClient.charge(unknown.getId(), 49_900, "tok_visa")).thenReturn(BankResult.approved("bnk_3"));
        when(transitions.recordBankResult(eq(unknown.getId()), any())).thenReturn(response(HttpStatus.CREATED));

        paymentService.create(MERCHANT, KEY, REQUEST);

        verify(transitions, never()).createPending(anyLong(), anyString(), any());
        verify(transitions).recordBankResult(eq(unknown.getId()), any());
    }

    @Test
    void retryFindingAPaymentTheReconcilerResolvedJustReturnsIt() {
        Payment captured = Payment.pending(MERCHANT, KEY, 49_900, "INR", "tok_visa", "order-1");
        captured.capture("bnk_4");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(MERCHANT, KEY)).thenReturn(Optional.of(captured));
        when(transitions.completeKeyForResolved(captured.getId()))
                .thenReturn(IdempotentResponse.fresh(HttpStatus.CREATED, PaymentResponse.from(captured)));

        paymentService.create(MERCHANT, KEY, REQUEST);

        verify(transitions).completeKeyForResolved(captured.getId());
        verifyNoInteractions(bankClient);
    }

    @Test
    void unsupportedCurrencyIsRejectedBeforeClaimingTheKey() {
        assertThatThrownBy(() -> paymentService.create(MERCHANT, KEY, new CreatePaymentRequest(100L, "USD", "tok", "o")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        verify(idempotencyService, never()).claim(anyLong(), anyString(), anyString());
    }

    @Test
    void rejectedRefundFreesItsIdempotencyKey() {
        UUID paymentId = UUID.randomUUID();
        when(refundService.refund(MERCHANT, paymentId, KEY, 500))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDS_CAPTURED", "too much"));

        assertThatThrownBy(() -> paymentService.refund(MERCHANT, paymentId, KEY, new CreateRefundRequest(500L)))
                .isInstanceOf(ApiException.class);
        verify(idempotencyService).delete(MERCHANT, KEY);
    }

    @Test
    void createIsTimedByOutcome() {
        Payment captured = Payment.pending(MERCHANT, KEY, 49_900, "INR", "tok_visa", "order-1");
        captured.capture("bnk_5");
        when(bankClient.charge(pending.getId(), 49_900, "tok_visa")).thenReturn(BankResult.approved("bnk_5"));
        when(transitions.recordBankResult(eq(pending.getId()), any()))
                .thenReturn(IdempotentResponse.fresh(HttpStatus.CREATED, PaymentResponse.from(captured)));

        paymentService.create(MERCHANT, KEY, REQUEST);

        assertThat(createTimerCount("captured")).isEqualTo(1);
    }

    @Test
    void bankTimeoutIsTimedAsUnknown() {
        Payment unknown = Payment.pending(MERCHANT, KEY, 49_900, "INR", "tok_visa", "order-1");
        unknown.markUnknown();
        when(bankClient.charge(pending.getId(), 49_900, "tok_visa"))
                .thenThrow(new BankUnavailableException("timeout", new SocketTimeoutException()));
        when(transitions.markUnknown(pending.getId()))
                .thenReturn(IdempotentResponse.fresh(HttpStatus.ACCEPTED, PaymentResponse.from(unknown)));

        paymentService.create(MERCHANT, KEY, REQUEST);

        assertThat(createTimerCount("unknown")).isEqualTo(1);
    }

    @Test
    void rejectedCreateIsTimedAsRejected() {
        assertThatThrownBy(() -> paymentService.create(MERCHANT, KEY, new CreatePaymentRequest(100L, "USD", "tok", "o")))
                .isInstanceOf(ApiException.class);

        assertThat(createTimerCount("rejected")).isEqualTo(1);
    }

    private long createTimerCount(String outcome) {
        Timer timer = meterRegistry.find("payment.create").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private IdempotentResponse<PaymentResponse> response(HttpStatus status) {
        return IdempotentResponse.fresh(status, PaymentResponse.from(pending));
    }
}
