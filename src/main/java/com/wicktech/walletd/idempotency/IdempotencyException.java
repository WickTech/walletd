package com.wicktech.walletd.idempotency;

/** Problems with the Idempotency-Key header, mapped to HTTP problem responses in the api package. */
public abstract sealed class IdempotencyException extends RuntimeException {

    IdempotencyException(String message) {
        super(message);
    }

    public static final class MissingKey extends IdempotencyException {
        public MissingKey() {
            super("The Idempotency-Key header is required for this request");
        }
    }

    public static final class InvalidKey extends IdempotencyException {
        public InvalidKey() {
            super("Idempotency-Key must be 1 to 255 printable ASCII characters without spaces");
        }
    }

    /** The key was already used for a different request body or endpoint. */
    public static final class KeyReused extends IdempotencyException {
        public KeyReused() {
            super("This Idempotency-Key was already used with a different request");
        }
    }

    public static final class InProgress extends IdempotencyException {
        public InProgress() {
            super("A request with this Idempotency-Key is still being processed; retry shortly");
        }
    }
}
