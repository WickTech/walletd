package com.wicktech.walletd.webhook;

import com.wicktech.walletd.idempotency.IdempotencyService;
import com.wicktech.walletd.ledger.LedgerException;
import com.wicktech.walletd.ledger.LedgerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Validator;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives "payment captured" notifications and credits the wallet exactly once per payment id,
 * however many times the provider delivers (they retry until they see a 2xx). The signature is checked
 * on the raw bytes before anything is parsed.
 */
@RestController
@RequestMapping("/webhooks")
class PaymentWebhookController {

    private static final String SCOPE = "webhook:payments";

    record Ack(String status, UUID entryId) {
    }

    private final SignatureVerifier verifier;
    private final JsonMapper mapper;
    private final Validator validator;
    private final IdempotencyService idempotency;
    private final LedgerService ledger;
    private final MeterRegistry metrics;

    PaymentWebhookController(SignatureVerifier verifier, JsonMapper mapper, Validator validator,
            IdempotencyService idempotency, LedgerService ledger, MeterRegistry metrics) {
        this.metrics = metrics;
        this.verifier = verifier;
        this.mapper = mapper;
        this.validator = validator;
        this.idempotency = idempotency;
        this.ledger = ledger;
    }

    @PostMapping(value = "/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Payment provider callback (HMAC-signed, not bearer-authenticated)",
            description = "Header X-Walletd-Signature: sha256=hex(HMAC-SHA256(secret, timestamp + '.' + rawBody)), "
                    + "with X-Walletd-Timestamp in epoch seconds. Credits a wallet once per paymentId.")
    Ack payments(@RequestBody byte[] raw,
            @Parameter(description = "Epoch seconds, part of the signed payload")
            @RequestHeader(value = "X-Walletd-Timestamp", required = false) String timestamp,
            @Parameter(description = "sha256=<hex HMAC>")
            @RequestHeader(value = "X-Walletd-Signature", required = false) String signature) {
        verifier.verify(raw, timestamp, signature); // before parsing: never trust unsigned bytes

        PaymentEvent event;
        try {
            event = mapper.readValue(raw, PaymentEvent.class);
        } catch (JacksonException e) {
            throw new WebhookException.BadPayload("The body is not a valid payment event");
        }
        var problems = validator.validate(event);
        if (!problems.isEmpty()) {
            throw new WebhookException.BadPayload(problems.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().collect(Collectors.joining("; ")));
        }
        if (!"payment.captured".equals(event.type())) {
            metrics.counter("walletd.webhook.deliveries", "result", "ignored").increment();
            return new Ack("ignored", null); // acknowledged so the provider stops retrying
        }

        // The key is the payment id, so every redelivery of the same payment maps to one credit.
        var result = idempotency.execute(SCOPE, event.paymentId(),
                idempotency.fingerprint("payment.captured", new Object[] {event.accountId(), event.amountMinor(),
                        event.currency()}),
                UUID.class, () -> credit(event));
        var outcome = result.replayed() ? "duplicate" : "credited";
        metrics.counter("walletd.webhook.deliveries", "result", outcome).increment();
        return new Ack(outcome, result.body());
    }

    private UUID credit(PaymentEvent event) {
        var account = ledger.account(event.accountId());
        if (!account.getCurrency().equals(event.currency())) {
            throw new LedgerException.InvalidTransfer(
                    "Currency mismatch: wallet is " + account.getCurrency() + ", payment is " + event.currency());
        }
        return ledger.topUpInTx(event.accountId(), event.amountMinor(), "payment:" + event.paymentId())
                .entry().getId();
    }
}
