package com.wicktech.walletd.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All money movement goes through {@link #post}: one transaction that locks
 * both accounts, updates the balances, writes a balanced journal entry and
 * queues an outbox event.
 *
 * <p>Accounts are row-locked ({@code FOR UPDATE}) in id order, so concurrent
 * transfers on a busy wallet queue up instead of failing, and two transfers in
 * opposite directions can't deadlock. The {@code @Version} column stays as a
 * safety net: any write that slips past the locks fails and is retried.
 */
@Service
public class LedgerService {

    /** Counterparty for money entering from the payment gateway. */
    public static final String GATEWAY_OWNER = "system:gateway";
    static final int MAX_ATTEMPTS = 5;

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;

    public LedgerService(AccountRepository accounts, JournalEntryRepository entries, PostingRepository postings,
            OutboxRepository outbox, PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.entries = entries;
        this.postings = postings;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
    }

    public record Posted(JournalEntry entry, List<Posting> postings) {
    }

    public Account openAccount(String ownerRef, String currency) {
        try {
            return tx.execute(s -> accounts.saveAndFlush(new Account(ownerRef, AccountType.USER, currency)));
        } catch (DataIntegrityViolationException e) {
            throw new LedgerException.DuplicateAccount(ownerRef, currency);
        }
    }

    public Account account(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new LedgerException.AccountNotFound(id));
    }

    public List<Posting> history(UUID accountId, int limit) {
        account(accountId);
        return postings.findByAccountIdOrderByIdDesc(accountId, PageRequest.of(0, limit));
    }

    public Posted entry(UUID entryId) {
        return readOnly.execute(s -> entries.findById(entryId)
                .map(e -> new Posted(e, postings.findByJournalEntryIdOrderById(entryId)))
                .orElse(null));
    }

    /** Credits a user wallet from the gateway clearing account (the payment webhook calls this). */
    public Posted topUp(UUID accountId, long amountMinor, String reference) {
        return withRetry(() -> tx.execute(s -> topUpInTx(accountId, amountMinor, reference)));
    }

    public Posted transfer(UUID fromId, UUID toId, long amountMinor, String reference) {
        return withRetry(() -> tx.execute(s -> transferInTx(fromId, toId, amountMinor, reference)));
    }

    /**
     * Same as {@link #topUp} but joins the caller's transaction and does not retry: the caller owns
     * the transaction boundary (the idempotency layer stores its record in the same transaction).
     */
    public Posted topUpInTx(UUID accountId, long amountMinor, String reference) {
        requirePositive(amountMinor);
        var target = account(accountId);
        var gateway = systemAccount(GATEWAY_OWNER, target.getCurrency());
        return post(JournalKind.TOP_UP, gateway.getId(), accountId, amountMinor, reference);
    }

    /** Same as {@link #transfer} but joins the caller's transaction and does not retry. */
    public Posted transferInTx(UUID fromId, UUID toId, long amountMinor, String reference) {
        requirePositive(amountMinor);
        if (fromId.equals(toId)) {
            throw new LedgerException.InvalidTransfer("Source and destination accounts must differ");
        }
        return post(JournalKind.TRANSFER, fromId, toId, amountMinor, reference);
    }

    private Posted post(JournalKind kind, UUID debitId, UUID creditId, long amountMinor, String reference) {
        return Objects.requireNonNull(tx.execute(s -> {
            // Lock in a fixed order (by id) so opposite-direction transfers can't deadlock.
            var first = debitId.compareTo(creditId) < 0 ? debitId : creditId;
            var second = first.equals(debitId) ? creditId : debitId;
            var locked = List.of(lock(first), lock(second));
            var debit = locked.get(first.equals(debitId) ? 0 : 1);
            var credit = locked.get(first.equals(debitId) ? 1 : 0);
            if (!debit.getCurrency().equals(credit.getCurrency())) {
                throw new LedgerException.InvalidTransfer(
                        "Currency mismatch: " + debit.getCurrency() + " -> " + credit.getCurrency());
            }
            if (kind == JournalKind.TRANSFER && debit.getType() != AccountType.USER) {
                throw new LedgerException.InvalidTransfer("Transfers must start from a user wallet");
            }

            debit.apply(-amountMinor);
            credit.apply(amountMinor);

            var entry = entries.save(new JournalEntry(kind, debit.getCurrency(), reference));
            var legs = postings.saveAll(List.of(
                    new Posting(entry.getId(), debitId, -amountMinor),
                    new Posting(entry.getId(), creditId, amountMinor)));
            // Only ids, enums and numbers go into the payload, so plain
            // formatting can't produce invalid JSON.
            outbox.save(new OutboxEvent("journal_entry", entry.getId(), kind + "_POSTED", String.format(
                    "{\"entryId\":\"%s\",\"kind\":\"%s\",\"currency\":\"%s\",\"amountMinor\":%d,"
                            + "\"debitAccountId\":\"%s\",\"creditAccountId\":\"%s\"}",
                    entry.getId(), kind, entry.getCurrency(), amountMinor, debitId, creditId)));
            return new Posted(entry, legs);
        }));
    }

    private Account lock(UUID id) {
        return accounts.findForUpdate(id).orElseThrow(() -> new LedgerException.AccountNotFound(id));
    }

    /** Retries a whole transaction that lost a race; must be called outside any transaction. */
    public <T> T withRetry(Supplier<T> attempt) {
        for (int i = 1; ; i++) {
            try {
                return attempt.get();
            } catch (ConcurrencyFailureException e) { // version conflict, deadlock or lock timeout
                if (i == MAX_ATTEMPTS) {
                    throw new LedgerException.Contended();
                }
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20L * i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LedgerException.Contended();
                }
            }
        }
    }

    /** Finds or creates a system account; safe when two requests race to create it. */
    private Account systemAccount(String owner, String currency) {
        var existing = accounts.findByOwnerRefAndCurrencyAndType(owner, currency, AccountType.SYSTEM);
        if (existing.isPresent()) {
            return existing.get();
        }
        var create = new TransactionTemplate(tx.getTransactionManager());
        create.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return create.execute(s -> accounts.saveAndFlush(new Account(owner, AccountType.SYSTEM, currency)));
        } catch (DataIntegrityViolationException lostRace) {
            return accounts.findByOwnerRefAndCurrencyAndType(owner, currency, AccountType.SYSTEM).orElseThrow();
        }
    }

    private static void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new LedgerException.InvalidTransfer("amountMinor must be positive");
        }
    }
}
