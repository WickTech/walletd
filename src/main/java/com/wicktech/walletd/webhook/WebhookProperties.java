package com.wicktech.walletd.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secret    shared HMAC secret with the payment provider, at least 32 characters
 *                  (env WALLETD_WEBHOOK_SECRET)
 * @param tolerance how far the signed timestamp may be from now; older deliveries are rejected so a
 *                  captured request can't be replayed later
 */
@ConfigurationProperties("walletd.webhook")
public record WebhookProperties(String secret, @DefaultValue("5m") Duration tolerance) {
}
