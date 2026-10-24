package com.wicktech.walletd.outbox;

import java.time.Instant;
import java.util.UUID;

/** A domain event written by the ledger in the same transaction as the change it describes. */
public record OutboxMessage(long id, String aggregateType, UUID aggregateId, String eventType, String payload,
        Instant createdAt) {
}
