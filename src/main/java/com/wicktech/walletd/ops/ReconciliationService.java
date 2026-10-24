package com.wicktech.walletd.ops;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audits the ledger against itself. The database already enforces the rules; this catches what a bug,
 * a manual SQL fix or a bad migration could still break:
 * <ul>
 *   <li>every stored balance equals the sum of that account's postings;</li>
 *   <li>every journal entry sums to zero;</li>
 *   <li>per currency, all balances (wallets plus gateway accounts) sum to zero, i.e. money is
 *       conserved.</li>
 * </ul>
 * It reads from one REPEATABLE READ snapshot so transfers running at the same time can't cause false alarms.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record BalanceMismatch(UUID accountId, long storedMinor, long computedMinor) {
    }

    public record CurrencyImbalance(String currency, long sumMinor) {
    }

    public record Report(Instant ranAt, long accountsChecked, boolean ok, List<BalanceMismatch> balanceMismatches,
            List<UUID> unbalancedEntries, List<CurrencyImbalance> currencyImbalances) {

        public int discrepancies() {
            return balanceMismatches.size() + unbalancedEntries.size() + currencyImbalances.size();
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final Clock clock;
    private final AtomicReference<Report> last = new AtomicReference<>();

    ReconciliationService(JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(txManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    /** The most recent report, or null if none has run yet. */
    public Report last() {
        return last.get();
    }

    @Scheduled(cron = "${walletd.reconciliation.cron:0 0 2 * * *}")
    public void scheduledRun() {
        var report = reconcile();
        if (report.ok()) {
            log.info("reconciliation ok: {} accounts checked", report.accountsChecked());
        } else {
            log.error("RECONCILIATION FOUND {} DISCREPANCIES: {}", report.discrepancies(), report);
        }
    }

    public Report reconcile() {
        var report = snapshot.execute(status -> {
            long accounts = jdbc.queryForObject("select count(*) from accounts", Long.class);
            var mismatches = jdbc.query(
                    "select a.id, a.balance_minor, coalesce(sum(p.amount_minor), 0) "
                            + "from accounts a left join postings p on p.account_id = a.id "
                            + "group by a.id, a.balance_minor "
                            + "having a.balance_minor <> coalesce(sum(p.amount_minor), 0) order by a.id",
                    (rs, i) -> new BalanceMismatch(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)));
            var unbalanced = jdbc.query(
                    "select journal_entry_id from postings group by journal_entry_id "
                            + "having sum(amount_minor) <> 0 order by journal_entry_id",
                    (rs, i) -> rs.getObject(1, UUID.class));
            var imbalances = jdbc.query(
                    "select currency, sum(balance_minor) from accounts group by currency "
                            + "having sum(balance_minor) <> 0 order by currency",
                    (rs, i) -> new CurrencyImbalance(rs.getString(1).trim(), rs.getLong(2)));
            boolean ok = mismatches.isEmpty() && unbalanced.isEmpty() && imbalances.isEmpty();
            return new Report(clock.instant(), accounts, ok, mismatches, unbalanced, imbalances);
        });
        last.set(report);
        return report;
    }
}
