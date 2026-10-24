package com.wicktech.walletd.api;

import com.wicktech.walletd.api.Dtos.AccountResponse;
import com.wicktech.walletd.api.Dtos.EntryResponse;
import com.wicktech.walletd.api.Dtos.OpenAccountRequest;
import com.wicktech.walletd.api.Dtos.PostingResponse;
import com.wicktech.walletd.api.Dtos.TopUpRequest;
import com.wicktech.walletd.api.Dtos.TransferRequest;
import com.wicktech.walletd.idempotency.IdempotencyService;
import com.wicktech.walletd.ledger.LedgerService;
import com.wicktech.walletd.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
class LedgerController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AccessPolicy access;

    LedgerController(LedgerService ledger, IdempotencyService idempotency, AccessPolicy access) {
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.access = access;
    }

    @PostMapping("/accounts")
    @Operation(summary = "Open a wallet (for yourself; admins may name another owner)")
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest req, Authentication auth) {
        var owner = access.ownerForNewAccount(Caller.of(auth), req.ownerRef());
        var account = ledger.openAccount(owner, req.currency());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId())).body(AccountResponse.of(account));
    }

    @GetMapping("/accounts/{id}")
    @Operation(summary = "Balance and details of one of your wallets")
    AccountResponse get(@PathVariable UUID id, Authentication auth) {
        return AccountResponse.of(access.ownedAccount(Caller.of(auth), id));
    }

    @GetMapping("/accounts/{id}/postings")
    @Operation(summary = "Ledger history of one of your wallets, newest first")
    List<PostingResponse> history(@PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit, Authentication auth) {
        access.ownedAccount(Caller.of(auth), id);
        return ledger.history(id, limit).stream().map(PostingResponse::of).toList();
    }

    @PostMapping("/accounts/{id}/top-ups")
    @Operation(summary = "Credit a wallet from the payment gateway account (ADMIN only)")
    ResponseEntity<EntryResponse> topUp(@PathVariable UUID id, @Valid @RequestBody TopUpRequest req,
            @Parameter(required = true, description = "Unique per logical request, e.g. a UUID")
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String keyHeader, Authentication auth) {
        var caller = Caller.of(auth);
        var key = idempotency.requireKey(keyHeader);
        var result = idempotency.execute(caller.subject(), key,
                idempotency.fingerprint("POST /accounts/" + id + "/top-ups", req), EntryResponse.class,
                () -> EntryResponse.of(ledger.topUpInTx(id, req.amountMinor(), req.reference())));
        return created(result);
    }

    @PostMapping("/transfers")
    @Operation(summary = "Move money from one of your wallets to any wallet")
    ResponseEntity<EntryResponse> transfer(@Valid @RequestBody TransferRequest req,
            @Parameter(required = true, description = "Unique per logical request, e.g. a UUID")
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String keyHeader, Authentication auth) {
        var caller = Caller.of(auth);
        var key = idempotency.requireKey(keyHeader);
        access.ownedAccount(caller, req.fromAccountId()); // the source must be yours (404 otherwise)
        var result = idempotency.execute(caller.subject(), key, idempotency.fingerprint("POST /transfers", req),
                EntryResponse.class, () -> EntryResponse.of(
                        ledger.transferInTx(req.fromAccountId(), req.toAccountId(), req.amountMinor(),
                                req.reference())));
        return created(result);
    }

    @GetMapping("/journal-entries/{id}")
    @Operation(summary = "A journal entry with its postings, if it touches one of your wallets")
    ResponseEntity<EntryResponse> entry(@PathVariable UUID id, Authentication auth) {
        var posted = ledger.entry(id);
        var caller = Caller.of(auth);
        boolean visible = posted != null && (caller.admin() || posted.postings().stream().anyMatch(
                p -> ledger.account(p.getAccountId()).getOwnerRef().equals(caller.subject())));
        return visible ? ResponseEntity.ok(EntryResponse.of(posted)) : ResponseEntity.notFound().build();
    }

    private static ResponseEntity<EntryResponse> created(IdempotencyService.Result<EntryResponse> result) {
        var builder = ResponseEntity.created(URI.create("/journal-entries/" + result.body().id()));
        if (result.replayed()) {
            builder.header(REPLAYED, "true");
        }
        return builder.body(result.body());
    }
}
