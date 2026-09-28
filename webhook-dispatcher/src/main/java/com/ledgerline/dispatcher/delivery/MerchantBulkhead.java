package com.ledgerline.dispatcher.delivery;

import com.ledgerline.dispatcher.config.DispatcherProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * At most N webhook calls in flight per merchant (per instance). A merchant whose endpoint is slow
 * fills only its own N slots; the other delivery threads stay free for everyone else.
 * {@link #tryAcquire} never waits: a full bulkhead means "try later", via the retry topics.
 */
@Component
public class MerchantBulkhead {

    private final ConcurrentHashMap<Long, Semaphore> permits = new ConcurrentHashMap<>();
    private final int maxInFlight;

    @Autowired
    public MerchantBulkhead(DispatcherProperties properties) {
        this(properties.bulkheadMaxInFlight());
    }

    public MerchantBulkhead(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    public boolean tryAcquire(long merchantId) {
        return permits.computeIfAbsent(merchantId, id -> new Semaphore(maxInFlight)).tryAcquire();
    }

    /** Call exactly once for each successful {@link #tryAcquire}, in a {@code finally}. */
    public void release(long merchantId) {
        permits.get(merchantId).release();
    }
}
