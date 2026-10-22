package com.wicktech.walletd.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "owner_ref", nullable = false)
    private String ownerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    /** Optimistic lock: concurrent writers to the same account retry instead of overwriting. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String ownerRef, AccountType type, String currency) {
        this.id = UUID.randomUUID();
        this.ownerRef = ownerRef;
        this.type = type;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    /** Applies a posting amount: positive credits, negative debits. */
    void apply(long amountMinor) {
        long next = Math.addExact(balanceMinor, amountMinor);
        if (type == AccountType.USER && next < 0) {
            throw new LedgerException.InsufficientFunds(id, balanceMinor, -amountMinor);
        }
        balanceMinor = next;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerRef() {
        return ownerRef;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
