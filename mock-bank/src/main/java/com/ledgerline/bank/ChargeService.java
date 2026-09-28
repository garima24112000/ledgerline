package com.ledgerline.bank;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;

/**
 * Decides charges at random (or by magic card token) and remembers every decision by paymentId,
 * so a retried charge gets the same answer. Charges live in memory: a restart forgets them.
 */
@Service
public class ChargeService {

    static final String APPROVE_TOKEN = "tok_approve";
    static final String DECLINE_TOKEN = "tok_decline";
    static final String TIMEOUT_TOKEN = "tok_timeout";
    static final String DECLINE_REASON = "insufficient_funds";

    private final BankProperties properties;
    private final RandomGenerator random;
    private final Sleeper sleeper;
    private final ConcurrentMap<UUID, Charge> charges = new ConcurrentHashMap<>();

    public ChargeService(BankProperties properties, RandomGenerator random, Sleeper sleeper) {
        this.properties = properties;
        this.random = random;
        this.sleeper = sleeper;
    }

    /**
     * The decision is stored <em>before</em> sleeping. For a timeout that means the bank has
     * already charged the card when the caller gives up waiting, which is exactly the case the
     * gateway's reconciler exists for. A retry returns the stored decision straight away.
     */
    public ChargeResponse charge(ChargeRequest request) {
        Charge decided = decide(request);
        Charge existing = charges.putIfAbsent(request.paymentId(), decided);
        if (existing != null) {
            if (!existing.sameRequestAs(request)) {
                throw new ChargeConflictException("payment " + request.paymentId() + " was already charged with a different amount or card");
            }
            return existing.toResponse();
        }
        sleeper.sleep(decided.timesOut() ? properties.timeoutSleep() : randomLatency());
        return decided.toResponse();
    }

    public Optional<ChargeResponse> find(UUID paymentId) {
        return Optional.ofNullable(charges.get(paymentId)).map(Charge::toResponse);
    }

    private Charge decide(ChargeRequest request) {
        return switch (request.cardToken()) {
            case APPROVE_TOKEN -> Charge.of(request, ChargeStatus.APPROVED, false);
            case DECLINE_TOKEN -> Charge.of(request, ChargeStatus.DECLINED, false);
            case TIMEOUT_TOKEN -> Charge.of(request, ChargeStatus.APPROVED, true);
            default -> randomCharge(request);
        };
    }

    private Charge randomCharge(ChargeRequest request) {
        double roll = random.nextDouble();
        if (roll < properties.approveRate()) {
            return Charge.of(request, ChargeStatus.APPROVED, false);
        }
        if (roll < properties.approveRate() + properties.declineRate()) {
            return Charge.of(request, ChargeStatus.DECLINED, false);
        }
        // A timeout still has an outcome; it is drawn with the same approve:decline odds.
        double decided = properties.approveRate() + properties.declineRate();
        boolean approved = decided == 0 || random.nextDouble() * decided < properties.approveRate();
        return Charge.of(request, approved ? ChargeStatus.APPROVED : ChargeStatus.DECLINED, true);
    }

    private Duration randomLatency() {
        long min = properties.latencyMin().toMillis();
        long max = properties.latencyMax().toMillis();
        return Duration.ofMillis(min + random.nextLong(max - min + 1));
    }

    private record Charge(UUID paymentId, long amount, String cardToken, ChargeStatus status,
                          String bankReference, boolean timesOut) {

        static Charge of(ChargeRequest request, ChargeStatus status, boolean timesOut) {
            String reference = "bnk_" + UUID.randomUUID().toString().substring(0, 8);
            return new Charge(request.paymentId(), request.amount(), request.cardToken(), status, reference, timesOut);
        }

        boolean sameRequestAs(ChargeRequest request) {
            return amount == request.amount() && cardToken.equals(request.cardToken());
        }

        ChargeResponse toResponse() {
            String reason = status == ChargeStatus.DECLINED ? DECLINE_REASON : null;
            return new ChargeResponse(paymentId, status, reason, bankReference);
        }
    }
}
