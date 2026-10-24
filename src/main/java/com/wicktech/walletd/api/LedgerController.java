package com.wicktech.walletd.api;

import com.wicktech.walletd.api.Dtos.AccountResponse;
import com.wicktech.walletd.api.Dtos.EntryResponse;
import com.wicktech.walletd.api.Dtos.OpenAccountRequest;
import com.wicktech.walletd.api.Dtos.PostingResponse;
import com.wicktech.walletd.api.Dtos.TopUpRequest;
import com.wicktech.walletd.api.Dtos.TransferRequest;
import com.wicktech.walletd.idempotency.IdempotencyService;
import com.wicktech.walletd.ledger.LedgerService;
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
    /** Callers are not authenticated yet; step 5 replaces this with the token's subject. */
    private static final String SCOPE = "public";

    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    LedgerController(LedgerService ledger, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest req) {
        var account = ledger.openAccount(req.ownerRef(), req.currency());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId())).body(AccountResponse.of(account));
    }

    @GetMapping("/accounts/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.of(ledger.account(id));
    }

    @GetMapping("/accounts/{id}/postings")
    List<PostingResponse> history(@PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return ledger.history(id, limit).stream().map(PostingResponse::of).toList();
    }

    @PostMapping("/accounts/{id}/top-ups")
    ResponseEntity<EntryResponse> topUp(@PathVariable UUID id, @Valid @RequestBody TopUpRequest req,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String keyHeader) {
        var key = idempotency.requireKey(keyHeader);
        var result = idempotency.execute(SCOPE, key,
                idempotency.fingerprint("POST /accounts/" + id + "/top-ups", req), EntryResponse.class,
                () -> EntryResponse.of(ledger.topUpInTx(id, req.amountMinor(), req.reference())));
        return created(result);
    }

    @PostMapping("/transfers")
    ResponseEntity<EntryResponse> transfer(@Valid @RequestBody TransferRequest req,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String keyHeader) {
        var key = idempotency.requireKey(keyHeader);
        var result = idempotency.execute(SCOPE, key, idempotency.fingerprint("POST /transfers", req),
                EntryResponse.class, () -> EntryResponse.of(
                        ledger.transferInTx(req.fromAccountId(), req.toAccountId(), req.amountMinor(),
                                req.reference())));
        return created(result);
    }

    @GetMapping("/journal-entries/{id}")
    ResponseEntity<EntryResponse> entry(@PathVariable UUID id) {
        var posted = ledger.entry(id);
        return posted == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(EntryResponse.of(posted));
    }

    private static ResponseEntity<EntryResponse> created(IdempotencyService.Result<EntryResponse> result) {
        var builder = ResponseEntity.created(URI.create("/journal-entries/" + result.body().id()));
        if (result.replayed()) {
            builder.header(REPLAYED, "true");
        }
        return builder.body(result.body());
    }
}
