package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;

import com.wicktech.walletd.RecordingPublisherConfig.RecordingPublisher;
import com.wicktech.walletd.ledger.LedgerService;
import com.wicktech.walletd.outbox.OutboxMessage;
import com.wicktech.walletd.outbox.OutboxRelay;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@WalletdTest
class OutboxRelayTests {

    @Autowired
    LedgerService ledger;

    @Autowired
    OutboxRelay relay;

    @Autowired
    RecordingPublisher publisher;

    @Autowired
    JdbcTemplate jdbc;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static long amountOf(OutboxMessage m) {
        return JSON.readTree(m.payload()).get("amountMinor").asLong();
    }

    @BeforeEach
    void drainEarlierEvents() {
        publisher.reset();
        relay.poll(); // events left over from other tests
        publisher.reset();
    }

    private UUID wallet() {
        return ledger.openAccount("outbox-" + UUID.randomUUID(), "INR").getId();
    }

    private long unpublished() {
        return jdbc.queryForObject("select count(*) from outbox where published_at is null", Long.class);
    }

    @Test
    void ledgerChangesAreQueuedAndPublishedOnce() {
        var a = wallet();
        var b = wallet();
        ledger.topUp(a, 1_000, "seed");
        var transfer = ledger.transfer(a, b, 250, "pay").entry().getId();

        assertThat(unpublished()).isEqualTo(2);
        assertThat(relay.relayBatch()).isEqualTo(2);
        assertThat(relay.relayBatch()).isZero();                       // nothing left to send

        assertThat(publisher.published).extracting(OutboxMessage::eventType)
                .containsExactly("TOP_UP_POSTED", "TRANSFER_POSTED");
        var event = publisher.published.get(1);
        assertThat(event.aggregateType()).isEqualTo("journal_entry");
        assertThat(event.aggregateId()).isEqualTo(transfer);
        var payload = JSON.readTree(event.payload());
        assertThat(payload.get("amountMinor").asLong()).isEqualTo(250);
        assertThat(payload.get("kind").asString()).isEqualTo("TRANSFER");
        assertThat(payload.get("entryId").asString()).isEqualTo(transfer.toString());
        assertThat(unpublished()).isZero();
    }

    @Test
    void aFailingPublisherLosesNothingAndKeepsOrder() {
        var a = wallet();
        var b = wallet();
        ledger.topUp(a, 1_000, "one");
        ledger.transfer(a, b, 100, "two");
        ledger.transfer(a, b, 200, "three");

        publisher.failing = m -> amountOf(m) == 100;                                  // the second event
        assertThat(relay.relayBatch()).isEqualTo(1);                                // only the first went out
        assertThat(unpublished()).isEqualTo(2);

        publisher.failing = m -> false;                                             // broker is back
        assertThat(relay.relayBatch()).isEqualTo(2);
        assertThat(publisher.published).extracting(OutboxRelayTests::amountOf).containsExactly(1000L, 100L, 200L);
        assertThat(unpublished()).isZero();
    }

    @Test
    void parallelRelaysNeverPublishTheSameRowTwice() throws Exception {
        var a = wallet();
        var b = wallet();
        ledger.topUp(a, 1_000_000, "seed");
        for (int i = 0; i < 59; i++) {
            ledger.transfer(a, b, 1, "n" + i);
        }
        long expected = unpublished();
        assertThat(expected).isEqualTo(60);

        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            jobs.add(() -> {
                int total = 0;
                int n;
                while ((n = relay.relayBatch()) > 0) {
                    total += n;
                }
                return total;
            });
        }
        long sum = 0;
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var f : pool.invokeAll(jobs)) {
                sum += f.get();
            }
        }

        assertThat(sum).isEqualTo(expected);
        assertThat(publisher.published.stream().map(OutboxMessage::id).distinct()).hasSize((int) expected);
        assertThat(unpublished()).isZero();
    }
}
