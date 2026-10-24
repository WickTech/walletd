package com.wicktech.walletd.api;

import com.wicktech.walletd.ledger.Account;
import com.wicktech.walletd.ledger.AccountType;
import com.wicktech.walletd.ledger.JournalKind;
import com.wicktech.walletd.ledger.LedgerService;
import com.wicktech.walletd.ledger.Posting;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies. Amounts are integer minor units (paise for INR). */
final class Dtos {

    private Dtos() {
    }

    /** {@code ownerRef} may be omitted: it then defaults to the caller's token subject. */
    record OpenAccountRequest(
            @Size(min = 1, max = 100) String ownerRef,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code like INR") String currency) {
    }

    record TopUpRequest(@Positive long amountMinor, @Size(max = 200) String reference) {
    }

    record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @Positive long amountMinor,
            @Size(max = 200) String reference) {
    }

    record AccountResponse(UUID id, String ownerRef, AccountType type, String currency, long balanceMinor,
            Instant createdAt) {

        static AccountResponse of(Account a) {
            return new AccountResponse(a.getId(), a.getOwnerRef(), a.getType(), a.getCurrency(),
                    a.getBalanceMinor(), a.getCreatedAt());
        }
    }

    record PostingResponse(long id, UUID journalEntryId, UUID accountId, long amountMinor, Instant createdAt) {

        static PostingResponse of(Posting p) {
            return new PostingResponse(p.getId(), p.getJournalEntryId(), p.getAccountId(), p.getAmountMinor(),
                    p.getCreatedAt());
        }
    }

    record EntryResponse(UUID id, JournalKind kind, String currency, String reference, Instant createdAt,
            List<PostingResponse> postings) {

        static EntryResponse of(LedgerService.Posted posted) {
            var e = posted.entry();
            return new EntryResponse(e.getId(), e.getKind(), e.getCurrency(), e.getReference(), e.getCreatedAt(),
                    posted.postings().stream().map(PostingResponse::of).toList());
        }
    }
}
