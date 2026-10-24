package com.wicktech.walletd.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Checks {@code X-Walletd-Signature: sha256=<hex>}: HMAC-SHA256, keyed with the shared secret, over
 * {@code <timestamp>.<raw body>} where the timestamp is the {@code X-Walletd-Timestamp} header (epoch
 * seconds). Signing the timestamp lets us reject old deliveries; the comparison is constant-time.
 */
@Component
public class SignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final byte[] secret;
    private final WebhookProperties props;
    private final Clock clock;

    SignatureVerifier(WebhookProperties props, Clock clock) {
        var configured = props.secret();
        if (configured == null || configured.length() < 32) {
            throw new IllegalStateException(
                    "walletd.webhook.secret (env WALLETD_WEBHOOK_SECRET) must be set to at least 32 characters");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
        this.props = props;
        this.clock = clock;
    }

    public void verify(byte[] rawBody, String timestampHeader, String signatureHeader) {
        if (timestampHeader == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            throw new WebhookException.BadSignature();
        }
        long seconds;
        byte[] provided;
        try {
            seconds = Long.parseLong(timestampHeader);
            provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new WebhookException.BadSignature();
        }
        var age = java.time.Duration.between(Instant.ofEpochSecond(seconds), clock.instant()).abs();
        if (age.compareTo(props.tolerance()) > 0) {
            throw new WebhookException.BadSignature();
        }
        if (!MessageDigest.isEqual(sign(timestampHeader, rawBody), provided)) {
            throw new WebhookException.BadSignature();
        }
    }

    /** Public so tests and docs can produce the same signature a provider would. */
    public byte[] sign(String timestamp, byte[] rawBody) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // HmacSHA256 is required on every JVM
        }
    }
}
