package com.wicktech.walletd.security;

import org.springframework.security.core.Authentication;

/** Who is calling: the token's subject and whether it carries the ADMIN role. */
public record Caller(String subject, boolean admin) {

    public static Caller of(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + TokenIssuer.ROLE_ADMIN));
        return new Caller(authentication.getName(), admin);
    }
}
