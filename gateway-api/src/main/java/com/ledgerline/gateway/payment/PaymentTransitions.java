package com.ledgerline.gateway.payment;

import com.ledgerline.gateway.idempotency.IdempotencyService;
import com.ledgerline.gateway.idempotency.IdempotentResponse;
import com.ledgerline.gateway.ledger.LedgerService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database steps of a payment, one transaction each. They live apart from {@link PaymentService}
 * so the call to the bank happens between transactions: no connection or row lock is ever held
 * while waiting on the network.
 */
@Service
public class PaymentTransitions {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;

    public PaymentTransitions(PaymentRepository paymentRepository, LedgerService ledgerService,
                              IdempotencyService idempotencyService) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
    }

    /** Committed before the bank is called, so the reconciler can find the payment if we crash mid-call. */
    @Transactional
    public Payment createPending(long merchantId, String idempotencyKey, CreatePaymentRequest request) {
        return paymentRepository.save(Payment.pending(merchantId, idempotencyKey, request.amount(),
                request.currency(), request.cardToken(), request.merchantOrderId()));
    }

    /**
     * Records the bank's definite answer and completes the idempotency key. An approval does three
     * things in ONE transaction: payment CAPTURED, ledger capture entry, key COMPLETED with the
     * response. Either all of them happen or none do.
     */
    @Transactional
    public IdempotentResponse<PaymentResponse> recordBankResult(UUID paymentId, BankResult result) {
        Payment payment = load(paymentId);
        if (result.approved()) {
            payment.capture(result.bankReference());
            // Flush now: a concurrent transition fails on @Version here, before any ledger locks
            // are taken. It also locks the payment row before the accounts, the same order refunds use.
            paymentRepository.saveAndFlush(payment);
            ledgerService.capture(payment.getId(), payment.getMerchantId(), payment.getAmount());
        } else {
            payment.fail(result.declineReason(), result.bankReference());
        }
        return completeKey(payment);
    }

    /** No answer from the bank: the payment is UNKNOWN, and the key is released so a retry can re-drive it. */
    @Transactional
    public IdempotentResponse<PaymentResponse> markUnknown(UUID paymentId) {
        Payment payment = load(paymentId);
        payment.markUnknown();
        idempotencyService.release(payment.getMerchantId(), payment.getIdempotencyKey());
        return IdempotentResponse.fresh(HttpStatus.ACCEPTED, PaymentResponse.from(payment));
    }

    /** A retry found its payment already resolved (by the reconciler): store and return that outcome. */
    @Transactional
    public IdempotentResponse<PaymentResponse> completeKeyForResolved(UUID paymentId) {
        return completeKey(load(paymentId));
    }

    private IdempotentResponse<PaymentResponse> completeKey(Payment payment) {
        PaymentResponse response = PaymentResponse.from(payment);
        idempotencyService.complete(payment.getMerchantId(), payment.getIdempotencyKey(), HttpStatus.CREATED, response);
        return IdempotentResponse.fresh(HttpStatus.CREATED, response);
    }

    private Payment load(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("payment " + paymentId + " does not exist"));
    }
}
