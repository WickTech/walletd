package com.wicktech.walletd.security;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local demo helper that hands out tokens to anyone. Only registered when
 * {@code walletd.security.dev-token-endpoint=true}; production must leave it off and get tokens from a
 * real identity provider that signs with the shared secret.
 */
@RestController
@ConditionalOnProperty(name = "walletd.security.dev-token-endpoint", havingValue = "true")
class DevTokenController {

    /** {@code admin} may be omitted (then false); a primitive boolean would make a missing field a 400. */
    record TokenRequest(@NotBlank @Size(max = 100) String subject, Boolean admin) {
    }

    record TokenResponse(String token, String tokenType, long expiresInSeconds) {
    }

    private final TokenIssuer issuer;

    DevTokenController(TokenIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping("/dev/token")
    @Operation(summary = "Local demo only: mint a token for any subject")
    TokenResponse token(@Valid @RequestBody TokenRequest req) {
        return new TokenResponse(issuer.issue(req.subject(), Boolean.TRUE.equals(req.admin())), "Bearer", issuer.ttlSeconds());
    }
}
