package com.wicktech.walletd.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * walletd is a resource server: it verifies bearer JWTs (HS256, shared secret) and never stores
 * passwords. Roles come from the {@code roles} claim. Errors are RFC 9457 problem responses.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    private static final int MIN_SECRET_LENGTH = 32;

    @Bean
    SecretKey jwtKey(SecurityProperties props) {
        var secret = props.jwtSecret();
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "walletd.security.jwt-secret (env WALLETD_JWT_SECRET) must be set to at least "
                            + MIN_SECRET_LENGTH + " characters");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, SecurityProperties props) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), JwtValidators.createDefaultWithIssuer(props.issuer())));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JsonMapper mapper) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http.csrf(AbstractHttpConfigurer::disable) // bearer tokens in a header, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/docs", "/docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/dev/token").permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/**").permitAll() // HMAC-signed instead
                        .requestMatchers(HttpMethod.POST, "/accounts/*/top-ups").hasRole("ADMIN")
                        .requestMatchers("/admin/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((req, res, e) -> problem(mapper, res, HttpStatus.UNAUTHORIZED,
                                "Unauthorized", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, e) -> problem(mapper, res, HttpStatus.FORBIDDEN,
                                "Forbidden", "You are not allowed to do this")))
                .exceptionHandling(Customizer.withDefaults());
        return http.build();
    }

    private static void problem(JsonMapper mapper, HttpServletResponse res, HttpStatus status, String title,
            String detail) throws java.io.IOException {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE); // JSON is UTF-8 by definition
        res.getOutputStream().write(mapper.writeValueAsBytes(body)); // bytes: no charset suffix is added
    }
}
