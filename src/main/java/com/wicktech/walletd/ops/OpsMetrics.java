package com.wicktech.walletd.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Gauges computed when scraped: what an on-call person wants a graph and an alert for. */
@Component
class OpsMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;
    private final ReconciliationService reconciliation;

    OpsMetrics(JdbcTemplate jdbc, ReconciliationService reconciliation) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("walletd.outbox.pending", () -> pendingOutbox())
                .description("Outbox events not yet published; growing means the broker or relay is stuck")
                .register(registry);
        Gauge.builder("walletd.outbox.oldest.age.seconds", () -> oldestPendingAgeSeconds())
                .description("Age of the oldest unpublished outbox event")
                .register(registry);
        Gauge.builder("walletd.reconciliation.discrepancies", () -> {
                    var last = reconciliation.last();
                    return last == null ? 0 : last.discrepancies();
                })
                .description("Problems found by the latest ledger audit; anything above 0 needs a human")
                .register(registry);
        Gauge.builder("walletd.reconciliation.last.run.timestamp.seconds", () -> {
                    var last = reconciliation.last();
                    return last == null ? 0 : last.ranAt().getEpochSecond();
                })
                .description("When the latest ledger audit finished (0 = never in this process)")
                .register(registry);
    }

    private double pendingOutbox() {
        Long n = jdbc.queryForObject("select count(*) from outbox where published_at is null", Long.class);
        return n == null ? 0 : n;
    }

    private double oldestPendingAgeSeconds() {
        Double age = jdbc.queryForObject(
                "select coalesce(extract(epoch from now() - min(created_at)), 0)::float8 "
                        + "from outbox where published_at is null", Double.class);
        return age == null ? 0 : age;
    }
}
