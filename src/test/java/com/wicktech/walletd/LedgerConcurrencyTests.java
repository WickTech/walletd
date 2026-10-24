package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;

import com.wicktech.walletd.ledger.LedgerException;
import com.wicktech.walletd.ledger.LedgerService;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Randomized load against the service layer. Whatever the interleaving, the
 * ledger must stay conserved, wallets non-negative, and every stored balance
 * equal to the sum of its postings.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class LedgerConcurrencyTests {

    private static final int ACCOUNTS = 6;
    private static final int TRANSFERS = 200;
    private static final long OPENING_BALANCE = 1_000;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @RepeatedTest(3)
    void randomTransfersConserveMoney() throws Exception {
        long seed = System.nanoTime();
        var random = new Random(seed);

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            var account = ledger.openAccount("rand-" + UUID.randomUUID(), "INR");
            ledger.topUp(account.getId(), OPENING_BALANCE, "seed");
            ids.add(account.getId());
        }

        List<Callable<String>> jobs = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            var from = ids.get(random.nextInt(ACCOUNTS));
            var to = ids.get(random.nextInt(ACCOUNTS));
            long amount = 1 + random.nextInt(400); // often more than the wallet holds
            jobs.add(() -> {
                try {
                    ledger.transfer(from, to, amount, "rand");
                    return "ok";
                } catch (LedgerException.InsufficientFunds | LedgerException.InvalidTransfer e) {
                    return "rejected"; // expected: overdraft or from == to
                }
            });
        }

        List<String> outcomes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (var f : pool.invokeAll(jobs)) {
                outcomes.add(f.get()); // any other exception (e.g. Contended) fails the test
            }
        }
        var message = "seed=" + seed;

        assertThat(outcomes).as(message).contains("ok");
        long total = 0;
        for (var id : ids) {
            long stored = ledger.account(id).getBalanceMinor();
            long fromPostings = jdbc.queryForObject(
                    "select coalesce(sum(amount_minor), 0) from postings where account_id = ?", Long.class, id);
            assertThat(stored).as(message).isGreaterThanOrEqualTo(0).isEqualTo(fromPostings);
            total += stored;
        }
        assertThat(total).as(message).isEqualTo(ACCOUNTS * OPENING_BALANCE);

        // Every journal entry we created balances to zero.
        var unbalanced = jdbc.queryForObject(
                "select count(*) from (select journal_entry_id from postings group by journal_entry_id "
                        + "having sum(amount_minor) <> 0) t", Long.class);
        assertThat(unbalanced).as(message).isZero();
    }
}
