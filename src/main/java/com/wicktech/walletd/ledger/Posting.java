package com.wicktech.walletd.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One leg of a journal entry: positive credits the account, negative debits it. */
@Entity
@Immutable
@Table(name = "postings")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Posting() {
    }

    Posting(UUID journalEntryId, UUID accountId, long amountMinor) {
        this.journalEntryId = journalEntryId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
