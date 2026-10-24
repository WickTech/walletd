package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;

import com.wicktech.walletd.TestApi.Res;
import com.wicktech.walletd.security.TokenIssuer;
import com.wicktech.walletd.webhook.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@WalletdTest
class WebhookApiTests {

    @LocalServerPort
    int port;

    @Autowired
    SignatureVerifier signer;

    @Autowired
    TokenIssuer tokens;

    @Autowired
    JdbcTemplate jdbc;

    TestApi anonymous;
    TestApi admin;
    String account;

    @BeforeEach
    void setUp() {
        anonymous = new TestApi(port);
        admin = new TestApi(port, tokens.issue("admin", true));
        account = (String) admin.call("POST", "/accounts",
                Map.of("ownerRef", "hook-" + UUID.randomUUID(), "currency", "INR")).body().get("id");
    }

    private long balance() {
        return ((Number) admin.call("GET", "/accounts/" + account, null).body().get("balanceMinor")).longValue();
    }

    private static String event(String type, String paymentId, String accountId, long amount, String currency) {
        return "{\"eventId\":\"evt_1\",\"type\":\"" + type + "\",\"paymentId\":\"" + paymentId
                + "\",\"accountId\":\"" + accountId + "\",\"amountMinor\":" + amount + ",\"currency\":\""
                + currency + "\"}";
    }

    private Map<String, String> signedHeaders(String body, long timestamp) {
        var signature = HexFormat.of().formatHex(signer.sign(String.valueOf(timestamp),
                body.getBytes(StandardCharsets.UTF_8)));
        return Map.of("X-Walletd-Timestamp", String.valueOf(timestamp), "X-Walletd-Signature", "sha256=" + signature);
    }

    private Res deliver(String body) {
        return anonymous.call("POST", "/webhooks/payments", body, signedHeaders(body, Instant.now().getEpochSecond()));
    }

    @Test
    void aSignedPaymentCreditsTheWalletOnce() {
        var payment = "pay_" + UUID.randomUUID();
        var body = event("payment.captured", payment, account, 2_500, "INR");

        var first = deliver(body);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("status")).isEqualTo("credited");
        assertThat(balance()).isEqualTo(2_500);

        var redelivery = deliver(body);
        assertThat(redelivery.status()).isEqualTo(200);
        assertThat(redelivery.body().get("status")).isEqualTo("duplicate");
        assertThat(redelivery.body().get("entryId")).isEqualTo(first.body().get("entryId"));
        assertThat(balance()).isEqualTo(2_500);

        var reference = jdbc.queryForObject("select reference from journal_entries where id = ?::uuid",
                String.class, first.body().get("entryId"));
        assertThat(reference).isEqualTo("payment:" + payment);
    }

    @Test
    void tenParallelDeliveriesOfOnePaymentCreditOnce() throws Exception {
        var body = event("payment.captured", "pay_" + UUID.randomUUID(), account, 700, "INR");
        List<Callable<Res>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(() -> deliver(body));
        }
        List<Res> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(10)) {
            for (var f : pool.invokeAll(jobs)) {
                results.add(f.get());
            }
        }
        assertThat(results).allMatch(r -> r.status() == 200);
        assertThat(results.stream().filter(r -> "credited".equals(r.body().get("status")))).hasSize(1);
        assertThat(balance()).isEqualTo(700);
    }

    @Test
    void badSignaturesAreRejectedAndNothingIsCredited() {
        var body = event("payment.captured", "pay_" + UUID.randomUUID(), account, 999, "INR");
        long now = Instant.now().getEpochSecond();
        var good = signedHeaders(body, now);

        // no headers, wrong prefix, not hex, tampered body, wrong timestamp, stale timestamp
        assertThat(anonymous.call("POST", "/webhooks/payments", body).status()).isEqualTo(401);
        assertThat(anonymous.call("POST", "/webhooks/payments", body, Map.of("X-Walletd-Timestamp", "" + now,
                "X-Walletd-Signature", good.get("X-Walletd-Signature").replace("sha256=", "md5="))).status())
                .isEqualTo(401);
        assertThat(anonymous.call("POST", "/webhooks/payments", body, Map.of("X-Walletd-Timestamp", "" + now,
                "X-Walletd-Signature", "sha256=zzzz")).status()).isEqualTo(401);
        assertThat(anonymous.call("POST", "/webhooks/payments", body.replace("999", "99999"), good).status())
                .isEqualTo(401);
        assertThat(anonymous.call("POST", "/webhooks/payments", body, Map.of("X-Walletd-Timestamp", "" + (now + 1),
                "X-Walletd-Signature", good.get("X-Walletd-Signature"))).status()).isEqualTo(401);
        assertThat(anonymous.call("POST", "/webhooks/payments", body, signedHeaders(body, now - 3_600)).status())
                .isEqualTo(401);

        var problem = anonymous.call("POST", "/webhooks/payments", body);
        assertThat(problem.headers().getContentType().toString()).startsWith("application/problem+json");
        assertThat(balance()).isZero();
    }

    @Test
    void validSignatureButInvalidContentIsRejected() {
        assertThat(deliver("not json").status()).isEqualTo(400);
        assertThat(deliver(event("payment.captured", "pay 1", account, 100, "INR")).status()).isEqualTo(400);
        assertThat(deliver(event("payment.captured", "pay_x", account, -5, "INR")).status()).isEqualTo(400);
        assertThat(deliver(event("payment.captured", "pay_x", account, 100, "inr")).status()).isEqualTo(400);
        assertThat(deliver(event("payment.captured", "pay_x", "not-a-uuid", 100, "INR")).status()).isEqualTo(400);
        assertThat(balance()).isZero();
    }

    @Test
    void otherEventTypesAreAcknowledgedWithoutMovingMoney() {
        var r = deliver(event("payment.failed", "pay_" + UUID.randomUUID(), account, 100, "INR"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status")).isEqualTo("ignored");
        assertThat(balance()).isZero();
    }

    @Test
    void unknownWalletAndCurrencyMismatchAreRejected() {
        assertThat(deliver(event("payment.captured", "pay_" + UUID.randomUUID(), UUID.randomUUID().toString(), 100,
                "INR")).status()).isEqualTo(404);
        assertThat(deliver(event("payment.captured", "pay_" + UUID.randomUUID(), account, 100, "USD")).status())
                .isEqualTo(422);
        assertThat(balance()).isZero();
    }

    @Test
    void theSamePaymentIdWithDifferentDetailsIsRefused() {
        var payment = "pay_" + UUID.randomUUID();
        assertThat(deliver(event("payment.captured", payment, account, 100, "INR")).status()).isEqualTo(200);
        assertThat(deliver(event("payment.captured", payment, account, 200, "INR")).status()).isEqualTo(422);
        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void theWebhookNeedsNoBearerTokenButTheTopUpEndpointStillDoes() {
        assertThat(deliver(event("payment.failed", "pay_a", account, 1, "INR")).status()).isEqualTo(200);
        assertThat(anonymous.call("POST", "/accounts/" + account + "/top-ups", Map.of("amountMinor", 1),
                Map.of("Idempotency-Key", "k")).status()).isEqualTo(401);
    }
}
