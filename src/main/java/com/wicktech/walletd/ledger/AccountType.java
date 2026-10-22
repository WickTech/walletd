package com.wicktech.walletd.ledger;

public enum AccountType {
    /** A customer wallet. Its balance can never go negative. */
    USER,
    /** The other side of money entering or leaving the ledger, e.g. the payment gateway. */
    SYSTEM
}
