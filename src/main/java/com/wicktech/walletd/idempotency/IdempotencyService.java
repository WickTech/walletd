package com.wicktech.walletd.idempotency;

import com.wicktech.walletd.ledger.LedgerService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a request safe to retry. A client sends the same Idempotency-Key with the same body and gets
 * the stored response back instead of a second money movement.
 *
 * <p>The key row is claimed, the operation runs, and the response is stored, all in one transaction.
 * A concurrent request with the same key blocks on the unique index until the first one commits (then
 * replays its response) or rolls back (then runs itself). Failed requests are not stored, so a client
 * can fix the problem (for example top up) and retry with the same key.
 */
@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

    /** @param replayed true when the stored response of an earlier identical request was returned */
    public record Result<T>(T body, boolean replayed) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final LedgerService ledger;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;

    public IdempotencyService(JdbcTemplate jdbc, JsonMapper mapper, LedgerService ledger,
            PlatformTransactionManager txManager, MeterRegistry metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Validates the header value; null or blank means the header was not sent. */
    public String requireKey(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new IdempotencyException.MissingKey();
        }
        if (!VALID_KEY.matcher(headerValue).matches()) {
            throw new IdempotencyException.InvalidKey();
        }
        return headerValue;
    }

    /** Fingerprint of a request: the endpoint plus its canonical JSON body. */
    public String fingerprint(String endpoint, Object body) {
        return sha256(endpoint + "\n" + mapper.writeValueAsString(body));
    }

    public <T> Result<T> execute(String scope, String key, String fingerprint, Class<T> type,
            Supplier<T> operation) {
        return ledger.withRetry(() -> tx.execute(s -> run(scope, key, fingerprint, type, operation)));
    }

    private <T> Result<T> run(String scope, String key, String fingerprint, Class<T> type,
            Supplier<T> operation) {
        int claimed = jdbc.update(
                "insert into idempotency_keys (scope, idem_key, request_hash) values (?, ?, ?) "
                        + "on conflict do nothing", scope, key, fingerprint);

        if (claimed == 0) {
            List<StoredResponse> rows = jdbc.query(
                    "select request_hash, response_body::text from idempotency_keys "
                            + "where scope = ? and idem_key = ?",
                    (rs, i) -> new StoredResponse(rs.getString(1), rs.getString(2)), scope, key);
            var stored = rows.getFirst();
            if (!stored.requestHash().equals(fingerprint)) {
                throw new IdempotencyException.KeyReused();
            }
            if (stored.responseBody() == null) {
                throw new IdempotencyException.InProgress();
            }
            metrics.counter("walletd.idempotency.replays").increment();
            return new Result<>(mapper.readValue(stored.responseBody(), type), true);
        }

        T body = operation.get();
        jdbc.update("update idempotency_keys set response_status = ?, response_body = ?::jsonb "
                + "where scope = ? and idem_key = ?", 201, mapper.writeValueAsString(body), scope, key);
        return new Result<>(body, false);
    }

    private record StoredResponse(String requestHash, String responseBody) {
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is required on every JVM
        }
    }
}
