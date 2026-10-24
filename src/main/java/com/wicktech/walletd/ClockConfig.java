package com.wicktech.walletd;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** One injectable clock, so time-dependent code (webhook tolerance) is testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
