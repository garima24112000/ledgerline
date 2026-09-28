package com.ledgerline.gateway.payment;

import com.ledgerline.gateway.api.ApiException;
import com.ledgerline.gateway.idempotency.Claim;
import com.ledgerline.gateway.idempotency.IdempotencyService;
import com.ledgerline.gateway.idempotency.IdempotentResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates payments and refunds. Deliberately not {@code @Transactional}: each database step
 * is its own short transaction (in {@link PaymentTransitions}, {@link RefundService} and
 * {@link IdempotencyService}), and the bank call sits between them.
 */
@Service
public class PaymentService {

    static final String SUPPORTED_CURRENCY = "INR"; // the only currency with ledger accounts

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final IdempotencyService idempotencyService;
    private final PaymentRepository paymentRepository;
    private final PaymentQueryRepository paymentQueryRepository;
    private final PaymentTransitions transitions;
    private final RefundService refundService;
    private final BankClient bankClient;

    public PaymentService(IdempotencyService idempotencyService, PaymentRepository paymentRepository,
                          PaymentQueryRepository paymentQueryRepository, PaymentTransitions transitions,
                          RefundService refundService, BankClient bankClient) {
        this.idempotencyService = idempotencyService;
        this.paymentRepository = paymentRepository;
        this.paymentQueryRepository = paymentQueryRepository;
        this.transitions = transitions;
        this.refundService = refundService;
        this.bankClient = bankClient;
    }

    /**
     * <ol>
     *   <li>Claim the idempotency key (or replay / 409 / 422).</li>
     *   <li>Create the PENDING payment, unless a take-over retry finds the one it created before.</li>
     *   <li>Charge the card, then record CAPTURED / FAILED, or UNKNOWN if the bank didn't answer.</li>
     * </ol>
     */
    public IdempotentResponse<PaymentResponse> create(long merchantId, String idempotencyKey, CreatePaymentRequest request) {
        if (!SUPPORTED_CURRENCY.equals(request.currency())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CURRENCY",
                    "Currency " + request.currency() + " is not supported; use " + SUPPORTED_CURRENCY);
        }
        String requestHash = idempotencyService.requestHash("POST /v1/payments", request);
        Claim claim = idempotencyService.claim(merchantId, idempotencyKey, requestHash);
        if (claim instanceof Claim.Replay replay) {
            return idempotencyService.replay(replay, PaymentResponse.class);
        }

        // After a timeout the key is released and a retry takes it over. That retry must drive the
        // payment it created the first time, never a second one.
        Payment payment = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .orElseGet(() -> transitions.createPending(merchantId, idempotencyKey, request));
        if (payment.isResolved()) {
            return transitions.completeKeyForResolved(payment.getId());
        }

        BankResult result;
        try {
            result = bankClient.charge(payment.getId(), payment.getAmount(), payment.getCardToken());
        } catch (BankUnavailableException e) {
            log.warn("No answer from the bank for payment {}; marking it UNKNOWN", payment.getId(), e);
            return transitions.markUnknown(payment.getId());
        }
        return transitions.recordBankResult(payment.getId(), result);
    }

    public PaymentResponse get(long merchantId, UUID paymentId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> paymentNotFound(paymentId));
    }

    /** Keyset pagination: fetch one row more than asked for, to know whether there is a next page. */
    public PaymentPage list(long merchantId, PaymentStatus status, Instant from, Instant to, String cursor, int limit) {
        PaymentCursor after = cursor == null ? null : PaymentCursor.decode(cursor);
        List<PaymentResponse> rows = paymentQueryRepository.findPage(merchantId, status, from, to, after, limit + 1);
        if (rows.size() <= limit) {
            return new PaymentPage(rows, null);
        }
        List<PaymentResponse> page = List.copyOf(rows.subList(0, limit));
        return new PaymentPage(page, PaymentCursor.after(page.getLast()).encode());
    }

    public IdempotentResponse<RefundResponse> refund(long merchantId, UUID paymentId, String idempotencyKey,
                                                     CreateRefundRequest request) {
        String requestHash = idempotencyService.requestHash("POST /v1/payments/" + paymentId + "/refunds", request);
        Claim claim = idempotencyService.claim(merchantId, idempotencyKey, requestHash);
        if (claim instanceof Claim.Replay replay) {
            return idempotencyService.replay(replay, RefundResponse.class);
        }
        try {
            RefundResponse refund = refundService.refund(merchantId, paymentId, idempotencyKey, request.amount());
            return IdempotentResponse.fresh(HttpStatus.CREATED, refund);
        } catch (ApiException e) {
            // Rejected before anything changed: forget the key, so a corrected retry can use it.
            idempotencyService.delete(merchantId, idempotencyKey);
            throw e;
        }
    }

    static ApiException paymentNotFound(UUID paymentId) {
        return ApiException.notFound("Payment " + paymentId + " not found");
    }
}
