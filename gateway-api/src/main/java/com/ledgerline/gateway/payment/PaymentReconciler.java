package com.ledgerline.gateway.payment;

import com.ledgerline.gateway.idempotency.IdempotencyService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves payments whose outcome we never learned: UNKNOWN (the bank timed out) and PENDING ones
 * left behind by a crash between creating the payment and hearing from the bank. It asks the bank
 * what happened and records that.
 *
 * <p>Before touching a payment it takes the payment's idempotency-key lock, the same lock a client
 * retry takes. So a retry and the reconciler never drive the same payment at the same time.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final PaymentQueryRepository paymentQueryRepository;
    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotencyService;
    private final BankClient bankClient;
    private final PaymentTransitions transitions;
    private final ReconcilerProperties properties;

    public PaymentReconciler(PaymentQueryRepository paymentQueryRepository, PaymentRepository paymentRepository,
                             IdempotencyService idempotencyService, BankClient bankClient,
                             PaymentTransitions transitions, ReconcilerProperties properties) {
        this.paymentQueryRepository = paymentQueryRepository;
        this.paymentRepository = paymentRepository;
        this.idempotencyService = idempotencyService;
        this.bankClient = bankClient;
        this.transitions = transitions;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${gateway.reconciler.interval}", initialDelayString = "${gateway.reconciler.interval}")
    void scheduledRun() {
        if (properties.enabled()) {
            reconcileOnce();
        }
    }

    /** @return how many payments were resolved */
    public int reconcileOnce() {
        Instant cutoff = Instant.now().minus(properties.minAge());
        int resolved = 0;
        for (UUID paymentId : paymentQueryRepository.findUnresolvedIds(cutoff, properties.batchSize())) {
            if (reconcile(paymentId)) {
                resolved++;
            }
        }
        if (resolved > 0) {
            log.info("Reconciler resolved {} payment(s)", resolved);
        }
        return resolved;
    }

    private boolean reconcile(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        if (payment.isResolved()) {
            return false;
        }
        long merchantId = payment.getMerchantId();
        String key = payment.getIdempotencyKey();
        if (!idempotencyService.tryLock(merchantId, key)) {
            return false; // a client retry (or another instance) is driving this payment right now
        }
        try {
            Optional<BankResult> result = bankClient.lookup(paymentId);
            // Not found at the bank: it never received the charge, so no money moved.
            transitions.recordBankResult(paymentId, result.orElse(BankResult.NOT_RECEIVED));
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not reconcile payment {}; will try again", paymentId, e);
            idempotencyService.release(merchantId, key);
            return false;
        }
    }
}
