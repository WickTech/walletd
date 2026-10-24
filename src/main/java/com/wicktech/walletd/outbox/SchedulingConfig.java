package com.wicktech.walletd.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (outbox relay, reconciliation) run unless {@code walletd.scheduling.enabled=false}. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "walletd.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
