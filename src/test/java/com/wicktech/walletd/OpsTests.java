package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;

import com.wicktech.walletd.ops.IdempotencyKeyCleaner;
import com.wicktech.walletd.ops.ReconciliationService;
import com.wicktech.walletd.security.TokenIssuer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@WalletdTest
class OpsTests {

    @LocalServerPort
    int port;

    @Autowired
    TokenIssuer tokens;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    IdempotencyKeyCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    TestApi anonymous;
    TestApi admin;
    TestApi user;

    @BeforeEach
    void setUp() {
        // LedgerSchemaTests writes postings with raw SQL (no balance updates), which the audit rightly
        // flags, so start from an empty ledger. TRUNCATE bypasses the append-only row triggers.
        jdbc.execute("truncate postings, journal_entries, accounts, outbox, idempotency_keys restart identity cascade");
        anonymous = new TestApi(port);
        admin = new TestApi(port, tokens.issue("admin", true));
        user = new TestApi(port, tokens.issue("plain-" + UUID.randomUUID(), false));
    }

    private static Map<String, String> key() {
        return Map.of("Idempotency-Key", UUID.randomUUID().toString());
    }

    private String wallet() {
        return (String) admin.call("POST", "/accounts",
                Map.of("ownerRef", "ops-" + UUID.randomUUID(), "currency", "INR")).body().get("id");
    }

    @Test
    void aHealthyLedgerReconcilesCleanly() {
        var a = wallet();
        var b = wallet();
        admin.call("POST", "/accounts/" + a + "/top-ups", Map.of("amountMinor", 5_000), key());
        admin.call("POST", "/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amountMinor", 1_200), key());

        var report = reconciliation.reconcile();

        assertThat(report.ok()).as(report.toString()).isTrue();
        assertThat(report.accountsChecked()).isGreaterThanOrEqualTo(3);   // two wallets + gateway
        assertThat(report.discrepancies()).isZero();
        assertThat(reconciliation.last()).isSameAs(report);
    }

    @Test
    void tamperingWithABalanceIsDetected() {
        var a = wallet();
        admin.call("POST", "/accounts/" + a + "/top-ups", Map.of("amountMinor", 1_000), key());
        jdbc.update("update accounts set balance_minor = balance_minor + 7 where id = ?::uuid", a); // "manual fix"
        try {
            var report = reconciliation.reconcile();

            assertThat(report.ok()).isFalse();
            assertThat(report.balanceMismatches()).singleElement().satisfies(m -> {
                assertThat(m.accountId()).hasToString(a);
                assertThat(m.storedMinor()).isEqualTo(1_007);
                assertThat(m.computedMinor()).isEqualTo(1_000);
            });
            assertThat(report.currencyImbalances()).singleElement().satisfies(c -> {
                assertThat(c.currency()).isEqualTo("INR");
                assertThat(c.sumMinor()).isEqualTo(7);        // money appeared from nowhere
            });
            assertThat(report.discrepancies()).isEqualTo(2);
        } finally {
            jdbc.update("update accounts set balance_minor = balance_minor - 7 where id = ?::uuid", a);
        }
        assertThat(reconciliation.reconcile().ok()).isTrue();
    }

    @Test
    void theAuditEndpointIsAdminOnly() {
        var r = admin.call("GET", "/admin/reconciliation", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("ok")).isEqualTo(true);
        assertThat(user.call("GET", "/admin/reconciliation", null).status()).isEqualTo(403);
        assertThat(anonymous.call("GET", "/admin/reconciliation", null).status()).isEqualTo(401);
    }

    @Test
    void metricsExposeLedgerActivityAndAreAdminOnly() {
        var a = wallet();
        var b = wallet();
        var topUpKey = key();
        admin.call("POST", "/accounts/" + a + "/top-ups", Map.of("amountMinor", 100), topUpKey);
        admin.call("POST", "/accounts/" + a + "/top-ups", Map.of("amountMinor", 100), topUpKey);        // replay
        admin.call("POST", "/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amountMinor", 50), key());
        admin.call("POST", "/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amountMinor", 9_999), key());
        reconciliation.reconcile();

        var metrics = admin.call("GET", "/actuator/prometheus", null);
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.text())
                .contains("walletd_ledger_entries_total{kind=\"TRANSFER\"")
                .contains("walletd_ledger_entries_total{kind=\"TOP_UP\"")
                .contains("walletd_idempotency_replays_total")
                .contains("walletd_requests_rejected_total{reason=\"InsufficientFunds\"")
                .contains("walletd_outbox_pending")
                .contains("walletd_outbox_oldest_age_seconds")
                .contains("walletd_reconciliation_discrepancies 0.0");
        assertThat(user.call("GET", "/actuator/prometheus", null).status()).isEqualTo(403);
        assertThat(anonymous.call("GET", "/actuator/prometheus", null).status()).isEqualTo(401);
        assertThat(anonymous.call("GET", "/actuator/health", null).status()).isEqualTo(200);
    }

    @Test
    void expiredClientKeysAreRemovedButWebhookKeysAreKept() {
        var tag = UUID.randomUUID().toString();
        jdbc.update("insert into idempotency_keys (scope, idem_key, request_hash, created_at) values "
                + "('user-" + tag + "', 'old', 'h', now() - interval '2 days'), "
                + "('webhook:payments', 'pay_old_" + tag + "', 'h', now() - interval '400 days'), "
                + "('user-" + tag + "', 'fresh', 'h', now())");

        int removed = cleaner.purge();

        assertThat(removed).isEqualTo(1);
        List<String> left = jdbc.queryForList(
                "select idem_key from idempotency_keys where idem_key in ('old', 'fresh', 'pay_old_" + tag
                        + "') order by 1", String.class);
        assertThat(left).containsExactly("fresh", "pay_old_" + tag);
    }
}
