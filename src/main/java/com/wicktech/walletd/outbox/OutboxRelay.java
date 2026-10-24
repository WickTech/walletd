package com.wicktech.walletd.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves events from the outbox table to an {@link OutboxPublisher}. Rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several instances can poll at once without publishing a row
 * twice at the same time. A crash between publishing and committing re-sends those rows (at least
 * once). Order follows the row id for a single relay; with several, consumers must not rely on it.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxPublisher publisher;
    private final int batchSize;

    OutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager txManager, OutboxPublisher publisher,
            @Value("${walletd.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /** Publishes until the outbox is empty or a publish fails. */
    @Scheduled(fixedDelayString = "${walletd.outbox.poll-interval:2s}")
    public void poll() {
        int published;
        do {
            published = relayBatch();
        } while (published == batchSize);
    }

    /** Publishes up to one batch; returns how many were published and marked done. */
    public int relayBatch() {
        Integer done = tx.execute(status -> {
            List<OutboxMessage> rows = jdbc.query(
                    "select id, aggregate_type, aggregate_id, event_type, payload::text, created_at "
                            + "from outbox where published_at is null order by id limit ? for update skip locked",
                    (rs, i) -> new OutboxMessage(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class),
                            rs.getString(4), rs.getString(5), rs.getObject(6, java.time.OffsetDateTime.class).toInstant()),
                    batchSize);
            int count = 0;
            for (var message : rows) {
                try {
                    publisher.publish(message);
                } catch (RuntimeException e) {
                    log.warn("outbox event {} not published, will retry: {}", message.id(), e.toString());
                    break; // keep order: later rows wait for this one
                }
                jdbc.update("update outbox set published_at = ? where id = ?", java.sql.Timestamp.from(Instant.now()),
                        message.id());
                count++;
            }
            return count;
        });
        return done == null ? 0 : done;
    }
}
