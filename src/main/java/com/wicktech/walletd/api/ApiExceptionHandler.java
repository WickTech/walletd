package com.wicktech.walletd.api;

import com.wicktech.walletd.idempotency.IdempotencyException;
import io.micrometer.core.instrument.MeterRegistry;
import com.wicktech.walletd.ledger.LedgerException;
import com.wicktech.walletd.webhook.WebhookException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps ledger rule failures to RFC 9457 problem responses. */
@RestControllerAdvice
class ApiExceptionHandler {

    private final MeterRegistry metrics;

    ApiExceptionHandler(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    /** Spikes in one reason (insufficient funds, bad webhook signatures) are what alerts watch. */
    private void count(String name, Exception e) {
        metrics.counter(name, "reason", e.getClass().getSimpleName()).increment();
    }

    @ExceptionHandler(LedgerException.class)
    ProblemDetail ledger(LedgerException e) {
        count("walletd.requests.rejected", e);
        var status = switch (e) {
            case LedgerException.AccountNotFound ignored -> HttpStatus.NOT_FOUND;
            case LedgerException.DuplicateAccount ignored -> HttpStatus.CONFLICT;
            case LedgerException.Contended ignored -> HttpStatus.SERVICE_UNAVAILABLE;
            case LedgerException.InsufficientFunds ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            case LedgerException.InvalidTransfer ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2"));
        return problem;
    }

    @ExceptionHandler(IdempotencyException.class)
    ProblemDetail idempotency(IdempotencyException e) {
        count("walletd.requests.rejected", e);
        var status = switch (e) {
            case IdempotencyException.MissingKey ignored -> HttpStatus.BAD_REQUEST;
            case IdempotencyException.InvalidKey ignored -> HttpStatus.BAD_REQUEST;
            case IdempotencyException.KeyReused ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            case IdempotencyException.InProgress ignored -> HttpStatus.CONFLICT;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2"));
        return problem;
    }

    @ExceptionHandler(WebhookException.class)
    ProblemDetail webhook(WebhookException e) {
        count("walletd.webhook.rejected", e);
        var status = e instanceof WebhookException.BadSignature ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        var problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2"));
        return problem;
    }
}
