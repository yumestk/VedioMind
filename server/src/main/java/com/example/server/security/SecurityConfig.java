package com.example.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthCrypto authCrypto(@Value("${auth.key-file:${user.home}/.vediomind/auth.key}") String path) {
        return new AuthCrypto(Path.of(path));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        var encoder = new Pbkdf2PasswordEncoder("", 16, 600000, 256);
        encoder.setAlgorithm(Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder("pbkdf2", Map.of("pbkdf2", encoder));
    }

    @Bean
    AuthenticationManager authenticationManager(AuthAccounts accounts, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    JwtEncoder jwtEncoder(AuthCrypto crypto) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(crypto.signingKey()));
    }

    @Bean
    JwtDecoder jwtDecoder(
            AuthCrypto crypto,
            Clock clock,
            @Value("${auth.issuer:vediomind}") String issuer,
            @Value("${auth.audience:vediomind-api}") String audience) {
        var decoder =
                NimbusJwtDecoder.withSecretKey(crypto.signingKey())
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        var time = new JwtTimestampValidator(Duration.ofSeconds(30));
        time.setClock(clock);
        OAuth2TokenValidator<Jwt> claims =
                jwt -> {
                    boolean valid =
                            jwt.getAudience() != null
                                    && jwt.getAudience().contains(audience)
                                    && jwt.getSubject() != null
                                    && validUserId(jwt.getSubject())
                                    && jwt.getExpiresAt() != null
                                    && jwt.getIssuedAt() != null
                                    && jwt.getClaimAsString("sid") != null;
                    return valid
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(
                                    new OAuth2Error("invalid_token", "Invalid claims", null));
                };
        OAuth2TokenValidator<Jwt> expiry =
                jwt ->
                        jwt.getExpiresAt() != null
                                        && clock.instant()
                                                .isAfter(jwt.getExpiresAt().plusSeconds(30))
                                ? OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error(
                                                "access_token_expired",
                                                "Access credential expired",
                                                null))
                                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        time, new JwtIssuerValidator(issuer), claims, expiry));
        return decoder;
    }

    @Bean
    CookieCsrfTokenRepository csrfRepository(@Value("${auth.cookie-secure:true}") boolean secure) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").secure(secure).sameSite("Lax"));
        return repository;
    }

    @Bean
    CorsConfigurationSource corsSource(
            @Value("${auth.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
                    String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "DELETE", "PUT", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(
                List.of(
                        "Content-Type",
                        "Authorization",
                        "X-XSRF-TOKEN",
                        "X-Refresh-Request",
                        "X-Session-Activity"));
        config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CookieCsrfTokenRepository csrf,
            ObjectMapper json,
            CorsConfigurationSource corsSource)
            throws Exception {
        http.cors(cors -> cors.configurationSource(corsSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .csrf(
                        c ->
                                c.csrfTokenRepository(csrf)
                                        // Bearer authentication happens on every request; rotate
                                        // CSRF only at explicit login/logout.
                                        .sessionAuthenticationStrategy(
                                                new org.springframework.security.web.authentication
                                                        .session.NullAuthenticatedSessionStrategy())
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/user/csrf",
                                                "/user/login",
                                                "/user/register",
                                                "/user/refresh",
                                                "/user/logout",
                                                "/error")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        o ->
                                o.jwt(j -> {})
                                        .authenticationEntryPoint(
                                                (req, res, e) -> unauthorized(res, json, e)))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (req, res, ex) -> unauthorized(res, json, ex))
                                        .accessDeniedHandler(
                                                (req, res, ex) ->
                                                        writeError(
                                                                res,
                                                                json,
                                                                403,
                                                                ex instanceof CsrfException
                                                                        ? "CSRF_INVALID"
                                                                        : "ACCESS_DENIED",
                                                                "请求校验失败，请刷新后重试")))
                .headers(h -> h.cacheControl(c -> {}));
        return http.build();
    }

    private void unauthorized(HttpServletResponse res, ObjectMapper json, Exception ex)
            throws java.io.IOException {
        boolean expired = false;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException validation
                    && validation.getErrors().stream()
                            .anyMatch(error -> "access_token_expired".equals(error.getErrorCode())))
                expired = true;
        }
        writeError(
                res, json, 401, expired ? "ACCESS_TOKEN_EXPIRED" : "AUTH_REQUIRED", "请先登录或恢复登录状态");
    }

    private boolean validUserId(String subject) {
        try {
            return subject != null && Long.parseLong(subject) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static void writeError(
            HttpServletResponse res, ObjectMapper json, int status, String code, String detail)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        json.writeValue(
                res.getOutputStream(), Map.of("status", status, "code", code, "detail", detail));
    }
}
