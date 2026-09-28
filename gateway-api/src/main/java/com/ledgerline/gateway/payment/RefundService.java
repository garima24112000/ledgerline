package com.ledgerline.gateway.payment;

import com.ledgerline.gateway.api.ApiException;
import com.ledgerline.gateway.idempotency.IdempotencyService;
import com.ledgerline.gateway.ledger.LedgerService;
import com.ledgerline.gateway.outbox.EventType;
import com.ledgerline.gateway.outbox.OutboxService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The refund transaction. {@link PaymentService#refund} handles the idempotency key around it. */
@Service
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;

    public RefundService(PaymentRepository paymentRepository, RefundRepository refundRepository,
                         LedgerService ledgerService, IdempotencyService idempotencyService,
                         OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
    }

    /**
     * Everything in ONE transaction: refund row, payment.refunded_amount, reversing ledger entry,
     * {@code refund.created} outbox event, idempotency key COMPLETED.
     *
     * <p>The payment row is locked first ({@code FOR UPDATE}). Two concurrent refunds on the same
     * payment therefore run one after the other, and the second one reads the refunded amount the
     * first committed. Without the lock both would pass the "not more than captured" check. The
     * {@code CHECK (refunded_amount <= amount)} in Postgres is the backstop.
     */
    @Transactional
    public RefundResponse refund(long merchantId, UUID paymentId, String idempotencyKey, long amount) {
        Payment payment = paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
                .orElseThrow(() -> PaymentService.paymentNotFound(paymentId));
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_REFUNDABLE",
                    "Only CAPTURED payments can be refunded; this one is " + payment.getStatus());
        }
        if (amount > payment.refundableAmount()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDS_CAPTURED",
                    "Refund of " + amount + " exceeds the refundable amount of " + payment.refundableAmount());
        }

        long refundedBefore = payment.getRefundedAmount();
        Refund refund = refundRepository.save(Refund.of(payment, amount));
        payment.addRefund(amount);
        ledgerService.refund(paymentId, merchantId, amount, refundedBefore);

        RefundResponse response = RefundResponse.from(refund, payment);
        outboxService.append(merchantId, refund.getId(), EventType.REFUND_CREATED, response);
        idempotencyService.complete(merchantId, idempotencyKey, HttpStatus.CREATED, response);
        return response;
    }
}
