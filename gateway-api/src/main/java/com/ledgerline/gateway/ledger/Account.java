package com.ledgerline.gateway.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private OwnerType ownerType;

    private Long ownerId;

    @Enumerated(EnumType.STRING)
    private AccountType type;

    private String currency;

    // Read-only for JPA: only LedgerService changes balances, with SQL, while holding the row lock.
    @Column(insertable = false, updatable = false)
    private long balance;

    private boolean allowNegative;

    @Version
    private long version;

    protected Account() {
        // for JPA
    }

    private Account(OwnerType ownerType, Long ownerId, AccountType type, String currency, boolean allowNegative) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.type = type;
        this.currency = currency;
        this.allowNegative = allowNegative;
    }

    /** What the platform owes a merchant. Can never go negative. */
    public static Account merchantPayable(long merchantId, String currency) {
        return new Account(OwnerType.MERCHANT, merchantId, AccountType.MERCHANT_PAYABLE, currency, false);
    }

    public Long getId() {
        return id;
    }

    public OwnerType getOwnerType() {
        return ownerType;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }
}
