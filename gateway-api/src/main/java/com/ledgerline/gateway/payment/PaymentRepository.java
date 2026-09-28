package com.ledgerline.gateway.payment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Primary-key lookup, scoped to the merchant: another merchant's payment is simply not found. */
    Optional<Payment> findByIdAndMerchantId(UUID id, long merchantId);

    /** Served by UNIQUE (merchant_id, idempotency_key). */
    Optional<Payment> findByMerchantIdAndIdempotencyKey(long merchantId, String idempotencyKey);

    /** SELECT ... FOR UPDATE: serializes refunds of the same payment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id and p.merchantId = :merchantId")
    Optional<Payment> findByIdAndMerchantIdForUpdate(@Param("id") UUID id, @Param("merchantId") long merchantId);
}
