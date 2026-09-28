package com.ledgerline.bank;

import java.time.Duration;

/** Seam for simulated latency, so unit tests don't actually sleep. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper real() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
