package com.wicktech.walletd.outbox;

/**
 * Where outbox events go: Kafka, a queue, a webhook. The default only logs; supply another bean to
 * publish for real. Delivery is at least once, so consumers must tolerate duplicates (use
 * {@link OutboxMessage#id()} as the event id).
 */
public interface OutboxPublisher {

    /** Throw to signal failure: the message stays in the outbox and is retried on the next poll. */
    void publish(OutboxMessage message);
}
