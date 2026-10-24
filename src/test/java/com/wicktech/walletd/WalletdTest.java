package com.wicktech.walletd;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full application on a random port against a real PostgreSQL. Every test class uses this exact
 * configuration, so they all share one Spring context (and one database).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "walletd.security.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "walletd.security.dev-token-endpoint=true",
        "walletd.webhook.secret=webhook-secret-webhook-secret-0123456789",
        "walletd.scheduling.enabled=false", // tests call the jobs themselves
        "management.defaults.metrics.export.enabled=true", // Boot switches export off in tests by default
        "management.prometheus.metrics.export.enabled=true",
})
@Import({TestDatabaseConfig.class, RecordingPublisherConfig.class})
@interface WalletdTest {
}
