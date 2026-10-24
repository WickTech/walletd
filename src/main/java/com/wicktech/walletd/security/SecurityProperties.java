package com.wicktech.walletd.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code walletd.security}.
 *
 * @param jwtSecret         HS256 signing secret, at least 32 characters (env WALLETD_JWT_SECRET)
 * @param issuer            expected {@code iss} claim
 * @param tokenTtl          lifetime of tokens issued by this service
 * @param devTokenEndpoint  exposes POST /dev/token that mints tokens for anyone: local demos only,
 *                          never in production (env WALLETD_DEV_TOKENS)
 */
@ConfigurationProperties("walletd.security")
public record SecurityProperties(
        String jwtSecret,
        @DefaultValue("walletd") String issuer,
        @DefaultValue("1h") Duration tokenTtl,
        @DefaultValue("false") boolean devTokenEndpoint) {
}
