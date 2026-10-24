package com.wicktech.walletd.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxMessage m) {
        log.info("outbox event id={} type={} aggregate={}/{}", m.id(), m.eventType(), m.aggregateType(),
                m.aggregateId());
    }
}
