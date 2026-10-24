package com.wicktech.walletd;

import com.wicktech.walletd.outbox.OutboxMessage;
import com.wicktech.walletd.outbox.OutboxPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the logging publisher so tests can see (and break) what the relay publishes. */
@TestConfiguration(proxyBeanMethods = false)
class RecordingPublisherConfig {

    static class RecordingPublisher implements OutboxPublisher {
        final List<OutboxMessage> published = Collections.synchronizedList(new ArrayList<>());
        volatile Predicate<OutboxMessage> failing = m -> false;

        @Override
        public void publish(OutboxMessage message) {
            if (failing.test(message)) {
                throw new IllegalStateException("broker down");
            }
            published.add(message);
        }

        void reset() {
            published.clear();
            failing = m -> false;
        }
    }

    @Bean
    @Primary
    RecordingPublisher recordingPublisher() {
        return new RecordingPublisher();
    }
}
