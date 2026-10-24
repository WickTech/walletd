package com.wicktech.walletd;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.wicktech.walletd.TestApi.Res;
import com.wicktech.walletd.security.TokenIssuer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@WalletdTest
class AuthApiTests {

    @LocalServerPort
    int port;

    @Autowired
    TokenIssuer tokens;

    @Autowired
    JwtEncoder encoder;

    TestApi anonymous;
    TestApi admin;
    TestApi alice;
    TestApi bob;
    String aliceName;
    String bobName;

    @BeforeEach
    void clients() {
        aliceName = "alice-" + UUID.randomUUID();
        bobName = "bob-" + UUID.randomUUID();
        anonymous = new TestApi(port);
        admin = new TestApi(port, tokens.issue("admin", true));
        alice = new TestApi(port, tokens.issue(aliceName, false));
        bob = new TestApi(port, tokens.issue(bobName, false));
    }

    private static Map<String, String> key() {
        return Map.of("Idempotency-Key", UUID.randomUUID().toString());
    }

    private String openFor(TestApi api, String ownerRef) {
        var body = ownerRef == null ? Map.of("currency", "INR") : Map.of("currency", "INR", "ownerRef", ownerRef);
        var r = api.call("POST", "/accounts", body);
        assertThat(r.status()).isEqualTo(201);
        return (String) r.body().get("id");
    }

    private void fund(String accountId, long amount) {
        assertThat(admin.call("POST", "/accounts/" + accountId + "/top-ups", Map.of("amountMinor", amount), key())
                .status()).isEqualTo(201);
    }

    private Res transfer(TestApi api, String from, String to, long amount, Map<String, String> headers) {
        return api.call("POST", "/transfers",
                Map.of("fromAccountId", from, "toAccountId", to, "amountMinor", amount), headers);
    }

    private String token(Instant issuedAt, Instant expiresAt, String issuer, JwtEncoder with) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("mallory").issuedAt(issuedAt)
                .expiresAt(expiresAt).claim("roles", List.of("USER", "ADMIN")).build();
        return with.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Test
    void missingOrBadTokensGet401AsProblemJson() {
        var now = Instant.now();
        var otherKey = new SecretKeySpec("another-secret-another-secret-another!".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        var tokensThatMustFail = List.of(
                "garbage",
                token(now.minusSeconds(7200), now.minusSeconds(3600), "walletd", encoder),            // expired
                token(now, now.plusSeconds(600), "evil-issuer", encoder),                              // wrong issuer
                token(now, now.plusSeconds(600), "walletd", new NimbusJwtEncoder(new ImmutableSecret<>(otherKey))));

        var none = anonymous.call("GET", "/accounts/" + UUID.randomUUID(), null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.headers().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(none.body().get("status")).isEqualTo(401);

        for (var bad : tokensThatMustFail) {
            var r = new TestApi(port, bad).call("GET", "/accounts/" + UUID.randomUUID(), null);
            assertThat(r.status()).as(bad).isEqualTo(401);
        }
    }

    @Test
    void healthAndApiDocsArePublic() {
        assertThat(anonymous.call("GET", "/actuator/health", null).status()).isEqualTo(200);
        var docs = anonymous.call("GET", "/v3/api-docs", null);
        assertThat(docs.status()).isEqualTo(200);
        var paths = ((Map<?, ?>) docs.body().get("paths")).keySet().stream().map(String::valueOf).toList();
        assertThat(paths).contains("/transfers", "/accounts/{id}/top-ups", "/accounts");
        assertThat(String.valueOf(docs.body().get("components"))).contains("bearer");
    }

    @Test
    void aUserOpensWalletsOnlyForThemselves() {
        var own = alice.call("POST", "/accounts", Map.of("currency", "INR"));
        assertThat(own.status()).isEqualTo(201);
        assertThat(own.body().get("ownerRef")).isEqualTo(aliceName);

        var forBob = alice.call("POST", "/accounts", Map.of("currency", "USD", "ownerRef", bobName));
        assertThat(forBob.status()).isEqualTo(403);
        assertThat(forBob.headers().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(admin.call("POST", "/accounts", Map.of("currency", "USD", "ownerRef", bobName)).status())
                .isEqualTo(201);
    }

    @Test
    void walletsAreVisibleOnlyToTheirOwnerAndAdmins() {
        var aliceAccount = openFor(alice, null);
        assertThat(alice.call("GET", "/accounts/" + aliceAccount, null).status()).isEqualTo(200);
        assertThat(admin.call("GET", "/accounts/" + aliceAccount, null).status()).isEqualTo(200);
        assertThat(bob.call("GET", "/accounts/" + aliceAccount, null).status()).isEqualTo(404);
        assertThat(bob.call("GET", "/accounts/" + aliceAccount + "/postings", null).status()).isEqualTo(404);
    }

    @Test
    void onlyAdminsCanTopUp() {
        var account = openFor(alice, null);
        var body = Map.of("amountMinor", 500);
        assertThat(alice.call("POST", "/accounts/" + account + "/top-ups", body, key()).status()).isEqualTo(403);
        assertThat(anonymous.call("POST", "/accounts/" + account + "/top-ups", body, key()).status())
                .isEqualTo(401);
        fund(account, 500);
        assertThat(((Number) alice.call("GET", "/accounts/" + account, null).body().get("balanceMinor"))
                .longValue()).isEqualTo(500);
    }

    @Test
    void transfersMustStartFromYourOwnWallet() {
        var a = openFor(alice, null);
        var b = openFor(bob, null);
        fund(a, 1_000);
        fund(b, 1_000);

        var ok = transfer(alice, a, b, 300, key());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(transfer(alice, b, a, 100, key()).status()).isEqualTo(404);   // bob's wallet
        assertThat(transfer(bob, a, b, 100, key()).status()).isEqualTo(404);     // alice's wallet

        var entry = "/journal-entries/" + ok.body().get("id");
        assertThat(alice.call("GET", entry, null).status()).isEqualTo(200);
        assertThat(bob.call("GET", entry, null).status()).isEqualTo(200);        // receiver sees it
        var carol = new TestApi(port, tokens.issue("carol-" + UUID.randomUUID(), false));
        assertThat(carol.call("GET", entry, null).status()).isEqualTo(404);
        assertThat(admin.call("GET", entry, null).status()).isEqualTo(200);
    }

    @Test
    void idempotencyKeysDoNotCollideBetweenUsers() {
        var a = openFor(alice, null);
        var b = openFor(bob, null);
        var c = openFor(admin, "carol-" + UUID.randomUUID());
        fund(a, 1_000);
        fund(b, 1_000);
        var shared = Map.of("Idempotency-Key", "same-key-for-everyone");

        var fromAlice = transfer(alice, a, c, 100, shared);
        var fromBob = transfer(bob, b, c, 100, shared);

        assertThat(fromAlice.status()).isEqualTo(201);
        assertThat(fromBob.status()).isEqualTo(201);
        assertThat(fromBob.headers().getFirst("Idempotent-Replayed")).isNull();
        assertThat(fromBob.body().get("id")).isNotEqualTo(fromAlice.body().get("id"));
    }

    @Test
    void theDevTokenEndpointIssuesWorkingTokens() {
        var r = anonymous.call("POST", "/dev/token", Map.of("subject", "dave", "admin", false));
        assertThat(r.status()).isEqualTo(200);
        var dave = new TestApi(port, (String) r.body().get("token"));
        assertThat(dave.call("POST", "/accounts", Map.of("currency", "INR")).body().get("ownerRef"))
                .isEqualTo("dave");

        // "admin" may be left out: that means a plain user (and the docs' examples do exactly that)
        var plain = anonymous.call("POST", "/dev/token", Map.of("subject", "erin"));
        assertThat(plain.status()).isEqualTo(200);
        var erin = new TestApi(port, (String) plain.body().get("token"));
        assertThat(erin.call("GET", "/admin/reconciliation", null).status()).isEqualTo(403);
    }
}
