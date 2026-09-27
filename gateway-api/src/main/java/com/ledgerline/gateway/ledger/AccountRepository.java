package com.ledgerline.gateway.ledger;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Both lookups are served by the unique index accounts(owner_type, owner_id, type).
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByOwnerTypeAndOwnerIdAndType(OwnerType ownerType, Long ownerId, AccountType type);

    /** Platform accounts have no owner id. */
    Optional<Account> findByOwnerTypeAndOwnerIdIsNullAndType(OwnerType ownerType, AccountType type);
}
