package com.wicktech.walletd.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Payload of a payment provider delivery. Only {@code payment.captured} moves money. */
record PaymentEvent(
        @Size(max = 100) String eventId,
        @NotBlank @Size(max = 60) String type,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,100}$", message = "must be 1-100 of A-Z a-z 0-9 _ -") String paymentId,
        @NotNull UUID accountId,
        @Positive long amountMinor,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code") String currency) {
}
