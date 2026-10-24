package com.wicktech.walletd.ops;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints; the security config restricts /admin/** to ADMIN. */
@RestController
@RequestMapping("/admin")
class AdminController {

    private final ReconciliationService reconciliation;

    AdminController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Run the ledger audit now and return the report (ADMIN)")
    ReconciliationService.Report reconcile() {
        return reconciliation.reconcile();
    }
}
