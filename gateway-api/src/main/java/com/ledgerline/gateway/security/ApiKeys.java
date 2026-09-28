package com.ledgerline.gateway.security;

import com.ledgerline.gateway.common.Sha256;

public final class ApiKeys {

    private ApiKeys() {
    }

    /**
     * SHA-256 of the raw key, as lowercase hex. A plain hash (no salt, no bcrypt) is enough for API
     * keys: they are long random strings, not human passwords, so there is no dictionary to attack,
     * and a deterministic hash is what makes the lookup a single unique-index probe.
     */
    public static String hash(String rawKey) {
        return Sha256.hex(rawKey);
    }
}
