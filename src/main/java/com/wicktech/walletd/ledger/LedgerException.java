package com.wicktech.walletd.ledger;

import java.util.UUID;

/** Business-rule failures, mapped to HTTP problem responses in the api package. */
public sealed class LedgerException extends RuntimeException {

    LedgerException(String message) {
        super(message);
    }

    public static final class AccountNotFound extends LedgerException {
        public AccountNotFound(UUID id) {
            super("Account " + id + " not found");
        }
    }

    public static final class DuplicateAccount extends LedgerException {
        public DuplicateAccount(String ownerRef, String currency) {
            super("Owner " + ownerRef + " already has a " + currency + " wallet");
        }
    }

    public static final class InsufficientFunds extends LedgerException {
        public InsufficientFunds(UUID id, long balance, long requested) {
            super("Account " + id + " has " + balance + " minor units; " + requested + " requested");
        }
    }

    public static final class InvalidTransfer extends LedgerException {
        public InvalidTransfer(String message) {
            super(message);
        }
    }

    public static final class Contended extends LedgerException {
        public Contended() {
            super("Account is busy with concurrent updates; retry the request");
        }
    }
}
