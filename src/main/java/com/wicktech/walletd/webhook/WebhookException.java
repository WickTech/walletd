package com.wicktech.walletd.webhook;

/** Webhook delivery problems, mapped to HTTP problem responses in the api package. */
public abstract sealed class WebhookException extends RuntimeException {

    WebhookException(String message) {
        super(message);
    }

    /** Missing, malformed, stale or wrong signature. The reason is deliberately not detailed. */
    public static final class BadSignature extends WebhookException {
        public BadSignature() {
            super("Invalid webhook signature");
        }
    }

    public static final class BadPayload extends WebhookException {
        public BadPayload(String message) {
            super(message);
        }
    }
}
