package com.wicktech.walletd.api;

import com.wicktech.walletd.ledger.LedgerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps ledger rule failures to RFC 9457 problem responses. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ProblemDetail ledger(LedgerException e) {
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
}
