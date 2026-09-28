package com.ledgerline.gateway.merchant;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    /** API-key authentication. Served by the unique index on api_key_hash. */
    Optional<Merchant> findByApiKeyHash(String apiKeyHash);
}
