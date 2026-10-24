package com.wicktech.walletd.security;

import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Mints HS256 tokens with the same secret the API verifies. Used by the dev endpoint and tests. */
@Component
public class TokenIssuer {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private final JwtEncoder encoder;
    private final SecurityProperties props;

    TokenIssuer(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String issue(String subject, boolean admin) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(props.tokenTtl()))
                .claim("roles", admin ? List.of(ROLE_USER, ROLE_ADMIN) : List.of(ROLE_USER))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return props.tokenTtl().toSeconds();
    }
}
