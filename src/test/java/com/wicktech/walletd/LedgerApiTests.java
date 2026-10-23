package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabaseConfig.class)
class LedgerApiTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    RestClient http;

    record Res(int status, Map<?, ?> body) {
    }

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
    }

    private Res call(String method, String path, Object body) {
        var spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            var text = new String(res.getBody().readAllBytes());
            Map<?, ?> parsed = text.startsWith("{") ? new tools.jackson.databind.ObjectMapper().readValue(text, Map.class) : Map.of();
            return new Res(res.getStatusCode().value(), parsed);
        });
    }

    private String open(String currency) {
        var r = call("POST", "/accounts", Map.of("ownerRef", "user-" + UUID.randomUUID(), "currency", currency));
        assertThat(r.status()).isEqualTo(201);
        return (String) r.body().get("id");
    }

    private long balance(String id) {
        return ((Number) call("GET", "/accounts/" + id, null).body().get("balanceMinor")).longValue();
    }

    private Res topUp(String id, long amount) {
        return call("POST", "/accounts/" + id + "/top-ups", Map.of("amountMinor", amount, "reference", "test"));
    }

    private Res transfer(String from, String to, long amount) {
        return call("POST", "/transfers", Map.of("fromAccountId", from, "toAccountId", to, "amountMinor", amount));
    }

    @Test
    void openingAccountsValidatesInputAndRejectsDuplicates() {
        var r = call("POST", "/accounts", Map.of("ownerRef", "asha", "currency", "INR"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("type")).isEqualTo("USER");
        assertThat(((Number) r.body().get("balanceMinor")).longValue()).isZero();
        assertThat(call("POST", "/accounts", Map.of("ownerRef", "asha", "currency", "INR")).status()).isEqualTo(409);
        assertThat(call("POST", "/accounts", Map.of("ownerRef", "asha", "currency", "inr")).status()).isEqualTo(400);
        assertThat(call("GET", "/accounts/" + UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(call("GET", "/accounts/not-a-uuid", null).status()).isEqualTo(400);
    }

    @Test
    void topUpCreditsTheWalletFromTheGatewayAccount() {
        var a = open("INR");
        var r = topUp(a, 10_000);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("kind")).isEqualTo("TOP_UP");
        assertThat(balance(a)).isEqualTo(10_000);

        var postings = (List<?>) r.body().get("postings");
        assertThat(postings).hasSize(2);
        long sum = postings.stream().mapToLong(p -> ((Number) ((Map<?, ?>) p).get("amountMinor")).longValue()).sum();
        assertThat(sum).isZero();
        assertThat(topUp(a, 0).status()).isEqualTo(400);
    }

    @Test
    void transferMovesMoneyAndRecordsABalancedEntry() {
        var a = open("INR");
        var b = open("INR");
        topUp(a, 1_000);

        var r = transfer(a, b, 400);
        assertThat(r.status()).isEqualTo(201);
        assertThat(balance(a)).isEqualTo(600);
        assertThat(balance(b)).isEqualTo(400);

        var entry = call("GET", "/journal-entries/" + r.body().get("id"), null);
        assertThat(entry.status()).isEqualTo(200);
        assertThat((List<?>) entry.body().get("postings")).hasSize(2);

        var outbox = jdbc.queryForObject(
                "select count(*) from outbox where aggregate_id = ?::uuid and event_type = 'TRANSFER_POSTED'",
                Long.class, r.body().get("id"));
        assertThat(outbox).isEqualTo(1);
    }

    @Test
    void rejectedTransfersChangeNothing() {
        var a = open("INR");
        var b = open("INR");
        var usd = open("USD");
        topUp(a, 100);
        long entriesBefore = jdbc.queryForObject("select count(*) from journal_entries", Long.class);

        assertThat(transfer(a, b, 101).status()).isEqualTo(422);
        assertThat(transfer(a, a, 10).status()).isEqualTo(422);
        assertThat(transfer(a, usd, 10).status()).isEqualTo(422);
        assertThat(transfer(a, UUID.randomUUID().toString(), 10).status()).isEqualTo(404);
        assertThat(transfer(a, b, -5).status()).isEqualTo(400);

        assertThat(balance(a)).isEqualTo(100);
        assertThat(balance(b)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from journal_entries", Long.class)).isEqualTo(entriesBefore);
    }

    @Test
    void parallelTransfersNeverOverspendOrLoseMoney() throws Exception {
        var a = open("INR");
        var b = open("INR");
        topUp(a, 100);

        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> transfer(a, b, 10).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            for (var f : pool.invokeAll(jobs)) {
                statuses.add(f.get());
            }
        }

        long ok = statuses.stream().filter(s -> s == 201).count();
        // Row locks queue the requests: exactly the affordable ten succeed,
        // the rest see insufficient funds. Nothing fails from contention.
        assertThat(ok).isEqualTo(10);
        assertThat(statuses).filteredOn(s -> s != 201).allMatch(s -> s == 422);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(100);

        // Stored balances agree with the ledger.
        for (var id : List.of(a, b)) {
            var fromPostings = jdbc.queryForObject(
                    "select coalesce(sum(amount_minor), 0) from postings where account_id = ?::uuid", Long.class, id);
            assertThat(fromPostings).isEqualTo(balance(id));
        }
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        var a = open("INR");
        var b = open("INR");
        topUp(a, 1_000);
        topUp(b, 1_000);

        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean forward = i % 2 == 0;
            jobs.add(() -> forward ? transfer(a, b, 7).status() : transfer(b, a, 7).status());
        }
        try (var pool = Executors.newFixedThreadPool(20)) {
            for (var f : pool.invokeAll(jobs)) {
                assertThat(f.get()).isEqualTo(201);
            }
        }
        assertThat(balance(a) + balance(b)).isEqualTo(2_000);
        assertThat(balance(a)).isEqualTo(1_000);
    }
}
