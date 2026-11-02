package com.example.server.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
public class AuthTokens {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration ttl;
    private final String issuer;
    private final String audience;

    public AuthTokens(
            JwtEncoder encoder,
            Clock clock,
            @Value("${auth.access-seconds:600}") long seconds,
            @Value("${auth.issuer:vediomind}") String issuer,
            @Value("${auth.audience:vediomind-api}") String audience) {
        if (seconds < 60 || seconds > 3600)
            throw new IllegalArgumentException("Access lifetime must be 60..3600 seconds");
        this.encoder = encoder;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(seconds);
        this.issuer = issuer;
        this.audience = audience;
    }

    public record Access(String value, long expiresAt) {}

    public Access issue(AuthSessions.Grant grant) {
        var now = clock.instant();
        var expires = now.plus(ttl);
        if (expires.isAfter(grant.expiresAt())) expires = grant.expiresAt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(java.util.List.of(audience))
                        .subject(Long.toString(grant.userId()))
                        .issuedAt(now)
                        .expiresAt(expires)
                        .id(UUID.randomUUID().toString())
                        .claim("sid", grant.sessionId())
                        .build();
        String value =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        return new Access(value, expires.toEpochMilli());
    }
}
