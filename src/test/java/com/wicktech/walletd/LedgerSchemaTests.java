package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The double-entry invariants the database itself enforces (V1__init.sql). */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class LedgerSchemaTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private UUID account(String type, String currency) {
        var id = UUID.randomUUID();
        jdbc.update("insert into accounts (id, owner_ref, type, currency) values (?, ?, ?, ?)",
                id, "owner-" + id, type, currency);
        return id;
    }

    private UUID entry(String currency) {
        var id = UUID.randomUUID();
        jdbc.update("insert into journal_entries (id, kind, currency) values (?, 'TRANSFER', ?)", id, currency);
        return id;
    }

    private void post(UUID entry, UUID account, long amount) {
        jdbc.update("insert into postings (journal_entry_id, account_id, amount_minor) values (?, ?, ?)",
                entry, account, amount);
    }

    @Test
    void balancedEntryCommits() {
        var a = account("USER", "INR");
        var b = account("USER", "INR");
        var e = tx.execute(s -> {
            var id = entry("INR");
            post(id, a, -500);
            post(id, b, 500);
            return id;
        });
        assertThat(jdbc.queryForObject(
                "select sum(amount_minor) from postings where journal_entry_id = ?", Long.class, e)).isZero();
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        var a = account("USER", "INR");
        var b = account("USER", "INR");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            var id = entry("INR");
            post(id, a, -500);
            post(id, b, 400);
        })).hasStackTraceContaining("unbalanced");
    }

    @Test
    void singleLegEntryIsRejected() {
        var a = account("USER", "INR");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> post(entry("INR"), a, 100)))
                .hasStackTraceContaining("unbalanced");
    }

    @Test
    void mixedCurrenciesAreRejected() {
        var inr = account("USER", "INR");
        var usd = account("USER", "USD");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            var id = entry("INR");
            post(id, inr, -100);
            post(id, usd, 100);
        })).hasStackTraceContaining("mixes currencies");
    }

    @Test
    void userBalanceCannotGoNegativeButSystemBalanceCan() {
        var user = account("USER", "INR");
        var system = account("SYSTEM", "INR");
        assertThatThrownBy(() -> jdbc.update("update accounts set balance_minor = -1 where id = ?", user))
                .hasStackTraceContaining("user_balance_non_negative");
        jdbc.update("update accounts set balance_minor = -1 where id = ?", system);
    }

    @Test
    void postingsAreAppendOnly() {
        var a = account("USER", "INR");
        var b = account("USER", "INR");
        var e = tx.execute(s -> {
            var id = entry("INR");
            post(id, a, -100);
            post(id, b, 100);
            return id;
        });
        assertThatThrownBy(() -> jdbc.update("update postings set amount_minor = 1 where journal_entry_id = ?", e))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from journal_entries where id = ?", e))
                .hasStackTraceContaining("append-only");
    }
}
