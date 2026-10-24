package com.wicktech.walletd.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes client idempotency keys after a retention period (default 24 h): a client retries within
 * minutes, not weeks. Keys in the {@code webhook:} scope are never deleted: they are what stops a
 * payment provider's late redelivery from crediting a wallet twice, and they are tiny.
 */
@Component
public class IdempotencyKeyCleaner {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleaner.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retention;

    IdempotencyKeyCleaner(JdbcTemplate jdbc, Clock clock,
            @Value("${walletd.idempotency.retention:24h}") Duration retention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${walletd.idempotency.cleanup-interval:1h}", initialDelayString = "5m")
    public int purge() {
        var cutoff = OffsetDateTime.ofInstant(clock.instant().minus(retention), ZoneOffset.UTC);
        int removed = jdbc.update("delete from idempotency_keys where created_at < ? and scope not like 'webhook:%'",
                cutoff);
        if (removed > 0) {
            log.info("removed {} expired idempotency keys", removed);
        }
        return removed;
    }
}
