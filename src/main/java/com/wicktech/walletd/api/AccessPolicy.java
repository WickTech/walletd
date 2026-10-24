package com.wicktech.walletd.api;

import com.wicktech.walletd.ledger.Account;
import com.wicktech.walletd.ledger.LedgerException;
import com.wicktech.walletd.ledger.LedgerService;
import com.wicktech.walletd.security.Caller;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Who may see or spend what. A wallet belongs to the token subject equal to its {@code ownerRef};
 * ADMIN may act on any wallet. An account the caller may not use looks exactly like a missing one
 * (404), so ids can't be probed.
 */
@Component
class AccessPolicy {

    private final LedgerService ledger;

    AccessPolicy(LedgerService ledger) {
        this.ledger = ledger;
    }

    /** The account, if the caller owns it or is an admin; otherwise behaves as not found. */
    Account ownedAccount(Caller caller, UUID id) {
        var account = ledger.account(id);
        if (!caller.admin() && !account.getOwnerRef().equals(caller.subject())) {
            throw new LedgerException.AccountNotFound(id);
        }
        return account;
    }

    /** Owner a new wallet is opened for: the caller's own subject unless an admin names someone else. */
    String ownerForNewAccount(Caller caller, String requested) {
        if (requested == null || requested.equals(caller.subject())) {
            return caller.subject();
        }
        if (!caller.admin()) {
            throw new AccessDeniedException("Wallets can only be opened for yourself");
        }
        return requested;
    }
}
