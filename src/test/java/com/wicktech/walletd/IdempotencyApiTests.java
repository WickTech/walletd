package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;

import com.wicktech.walletd.TestApi.Res;
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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabaseConfig.class)
class IdempotencyApiTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestApi api;

    @BeforeEach
    void client() {
        api = new TestApi(port);
    }

    private String open() {
        return (String) api.call("POST", "/accounts",
                Map.of("ownerRef", "idem-" + UUID.randomUUID(), "currency", "INR")).body().get("id");
    }

    private long balance(String id) {
        return ((Number) api.call("GET", "/accounts/" + id, null).body().get("balanceMinor")).longValue();
    }

    private Res topUp(String id, long amount, String key) {
        return api.call("POST", "/accounts/" + id + "/top-ups", Map.of("amountMinor", amount),
                Map.of("Idempotency-Key", key));
    }

    private Res transfer(String from, String to, long amount, String key) {
        return api.call("POST", "/transfers",
                Map.of("fromAccountId", from, "toAccountId", to, "amountMinor", amount),
                Map.of("Idempotency-Key", key));
    }

    private long entryCount() {
        return jdbc.queryForObject("select count(*) from journal_entries", Long.class);
    }

    @Test
    void replayReturnsTheSameResponseAndMovesMoneyOnce() {
        var a = open();
        var b = open();
        topUp(a, 1_000, UUID.randomUUID().toString());
        var key = UUID.randomUUID().toString();

        var first = transfer(a, b, 300, key);
        long entries = entryCount();
        var second = transfer(a, b, 300, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.headers().getFirst("Idempotent-Replayed")).isNull();
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().getLocation()).isEqualTo(first.headers().getLocation());
        assertThat(entryCount()).isEqualTo(entries);
        assertThat(balance(a)).isEqualTo(700);
        assertThat(balance(b)).isEqualTo(300);
    }

    @Test
    void sameKeyWithADifferentBodyOrEndpointIsRejected() {
        var a = open();
        var b = open();
        topUp(a, 1_000, UUID.randomUUID().toString());
        var key = UUID.randomUUID().toString();
        assertThat(transfer(a, b, 100, key).status()).isEqualTo(201);

        assertThat(transfer(a, b, 200, key).status()).isEqualTo(422);       // other amount
        assertThat(topUp(a, 100, key).status()).isEqualTo(422);             // other endpoint
        assertThat(balance(a)).isEqualTo(900);
    }

    @Test
    void theKeyIsRequiredAndValidated() {
        var a = open();
        var body = Map.of("amountMinor", 10);
        assertThat(api.call("POST", "/accounts/" + a + "/top-ups", body).status()).isEqualTo(400);
        assertThat(topUp(a, 10, "has a space").status()).isEqualTo(400);
        assertThat(topUp(a, 10, "x".repeat(256)).status()).isEqualTo(400);
        assertThat(balance(a)).isZero();
    }

    @Test
    void failedRequestsAreNotStoredSoTheSameKeyCanBeRetried() {
        var a = open();
        var b = open();
        var key = UUID.randomUUID().toString();

        assertThat(transfer(a, b, 500, key).status()).isEqualTo(422);        // no funds yet
        topUp(a, 1_000, UUID.randomUUID().toString());
        var retry = transfer(a, b, 500, key);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.headers().getFirst("Idempotent-Replayed")).isNull();
        assertThat(balance(b)).isEqualTo(500);
    }

    @Test
    void twentyParallelRequestsWithOneKeyCreateExactlyOneEntry() throws Exception {
        var a = open();
        var b = open();
        topUp(a, 1_000, UUID.randomUUID().toString());
        var key = UUID.randomUUID().toString();
        long entries = entryCount();

        List<Callable<Res>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> transfer(a, b, 100, key));
        }
        List<Res> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            for (var f : pool.invokeAll(jobs)) {
                results.add(f.get());
            }
        }

        assertThat(results).allMatch(r -> r.status() == 201);
        assertThat(results.stream().map(r -> r.body().get("id")).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> r.headers().getFirst("Idempotent-Replayed") == null)).hasSize(1);
        assertThat(entryCount()).isEqualTo(entries + 1);
        assertThat(balance(a)).isEqualTo(900);
        assertThat(balance(b)).isEqualTo(100);
    }
}
