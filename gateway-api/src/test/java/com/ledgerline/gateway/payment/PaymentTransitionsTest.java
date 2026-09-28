package com.ledgerline.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ledgerline.gateway.idempotency.IdempotencyService;
import com.ledgerline.gateway.ledger.LedgerService;
import com.ledgerline.gateway.outbox.EventType;
import com.ledgerline.gateway.outbox.OutboxService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentTransitionsTest {

    private static final long MERCHANT = 7;

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private PaymentTransitions transitions;

    private final Payment payment = Payment.pending(MERCHANT, "key-1", 10_000, "INR", "tok", "order-1");

    @BeforeEach
    void setUp() {
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    @Test
    void approvalCapturesPostsToLedgerAndAppendsPaymentCaptured() {
        transitions.recordBankResult(payment.getId(), BankResult.approved("bnk_1"));

        InOrder order = inOrder(ledgerService, outboxService, idempotencyService);
        order.verify(ledgerService).capture(payment.getId(), MERCHANT, 10_000);
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        order.verify(outboxService).append(eq(MERCHANT), eq(payment.getId()), eq(EventType.PAYMENT_CAPTURED), data.capture());
        order.verify(idempotencyService).complete(eq(MERCHANT), eq("key-1"), any(), any());
        assertThat(((PaymentResponse) data.getValue()).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void declineAppendsPaymentFailedWithoutTouchingTheLedger() {
        transitions.recordBankResult(payment.getId(), BankResult.declined("insufficient_funds", "bnk_2"));

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(eq(MERCHANT), eq(payment.getId()), eq(EventType.PAYMENT_FAILED), data.capture());
        assertThat(((PaymentResponse) data.getValue()).declineReason()).isEqualTo("insufficient_funds");
        verify(ledgerService, never()).capture(any(), anyLong(), anyLong());
    }

    @Test
    void unknownIsNotAMerchantFacingEvent() {
        transitions.markUnknown(payment.getId());

        verifyNoInteractions(outboxService);
    }
}
