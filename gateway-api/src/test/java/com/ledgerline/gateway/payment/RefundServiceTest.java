package com.ledgerline.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ledgerline.gateway.api.ApiException;
import com.ledgerline.gateway.idempotency.IdempotencyService;
import com.ledgerline.gateway.ledger.LedgerService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final long MERCHANT = 7;

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private RefundService refundService;

    @Test
    void refundPostsReversingEntryWithWhatWasRefundedBefore() {
        Payment payment = captured(10_000);
        payment.addRefund(3_000);
        when(refundRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        RefundResponse response = refundService.refund(MERCHANT, payment.getId(), "k", 5_000);

        verify(ledgerService).refund(payment.getId(), MERCHANT, 5_000, 3_000);
        verify(idempotencyService).complete(MERCHANT, "k", HttpStatus.CREATED, response);
        assertThat(response.amount()).isEqualTo(5_000);
        assertThat(response.paymentRefundedAmount()).isEqualTo(8_000);
    }

    @Test
    void refundOfExactlyTheRestIsAllowed() {
        Payment payment = captured(10_000);
        payment.addRefund(3_000);
        when(refundRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(refundService.refund(MERCHANT, payment.getId(), "k", 7_000).paymentRefundedAmount()).isEqualTo(10_000);
    }

    @Test
    void refundOverTheRefundableAmountIsRejected() {
        Payment payment = captured(10_000);
        payment.addRefund(3_000);

        assertThatThrownBy(() -> refundService.refund(MERCHANT, payment.getId(), "k", 7_001))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REFUND_EXCEEDS_CAPTURED"));
        verifyNoInteractions(ledgerService, refundRepository);
    }

    @Test
    void onlyCapturedPaymentsCanBeRefunded() {
        Payment payment = Payment.pending(MERCHANT, "key", 10_000, "INR", "tok", "o");
        payment.fail("insufficient_funds", "bnk");
        when(paymentRepository.findByIdAndMerchantIdForUpdate(payment.getId(), MERCHANT)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundService.refund(MERCHANT, payment.getId(), "k", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anotherMerchantsPaymentIsNotFound() {
        Payment payment = captured(10_000);
        when(paymentRepository.findByIdAndMerchantIdForUpdate(payment.getId(), 8)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.refund(8, payment.getId(), "k", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Payment captured(long amount) {
        Payment payment = Payment.pending(MERCHANT, "key", amount, "INR", "tok", "o");
        payment.capture("bnk");
        lenient().when(paymentRepository.findByIdAndMerchantIdForUpdate(payment.getId(), MERCHANT))
                .thenReturn(Optional.of(payment));
        return payment;
    }
}
