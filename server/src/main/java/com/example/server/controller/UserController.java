package com.example.server.controller;

import com.example.server.security.*;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {
    private final JdbcTemplate jdbc;
    private final AuthenticationManager manager;
    private final PasswordEncoder passwords;
    private final AuthAccounts accounts;
    private final AuthSessions sessions;
    private final AuthTokens tokens;
    private final AuthRateLimits limits;
    private final Clock clock;
    private final boolean secure;
    private final String cookiePath;
    private final org.springframework.security.web.csrf.CookieCsrfTokenRepository csrf;

    public UserController(
            JdbcTemplate jdbc,
            AuthenticationManager manager,
            PasswordEncoder passwords,
            AuthAccounts accounts,
            AuthSessions sessions,
            AuthTokens tokens,
            AuthRateLimits limits,
            Clock clock,
            org.springframework.security.web.csrf.CookieCsrfTokenRepository csrf,
            @Value("${auth.cookie-secure:true}") boolean secure,
            @Value("${auth.cookie-path:/api/user}") String cookiePath) {
        this.jdbc = jdbc;
        this.manager = manager;
        this.passwords = passwords;
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokens = tokens;
        this.limits = limits;
        this.clock = clock;
        this.secure = secure;
        this.cookiePath = cookiePath;
        this.csrf = csrf;
    }

    public record Register(
            @NotBlank @Size(min = 3, max = 100) @Pattern(regexp = "[A-Za-z0-9_]+") String username,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 100) String nickname) {}

    public record Login(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 128) String password) {}

    public record Result(String accessToken, long expiresAt, AuthAccounts.Profile userInfo) {}

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrf, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(
            @Valid @RequestBody Register input, HttpServletRequest request) {
        limits.require("register", request.getRemoteAddr(), null);
        jdbc.update(
                "INSERT INTO users(username,password,nickname,role) VALUES(?,?,?,?)",
                input.username(),
                passwords.encode(input.password()),
                input.nickname() == null || input.nickname().isBlank()
                        ? input.username()
                        : input.nickname(),
                "USER");
        return Map.of("message", "注册成功，请登录");
    }

    @PostMapping("/login")
    public Result login(
            @Valid @RequestBody Login input,
            HttpServletRequest request,
            HttpServletResponse response) {
        limits.require("login", request.getRemoteAddr(), input.username());
        try {
            var auth =
                    manager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    input.username(), input.password()));
            var grant = sessions.create(Long.parseLong(auth.getName()));
            var result = result(grant, response);
            csrf.saveToken(null, request, response);
            return result;
        } catch (AuthenticationServiceException ex) {
            throw new AuthException(503, "AUTH_STORE_UNAVAILABLE", "登录服务暂时不可用");
        } catch (AuthenticationException | IllegalArgumentException ex) {
            throw new AuthException(401, "INVALID_CREDENTIALS", "账号或密码错误");
        }
    }

    @PostMapping("/refresh")
    public Result refresh(
            @CookieValue(name = "vm_refresh", required = false) String token,
            @RequestHeader("X-Refresh-Request") String requestId,
            @RequestHeader(value = "X-Session-Activity", defaultValue = "false") boolean active,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (token == null) throw new AuthException(401, "SESSION_EXPIRED", "请登录");
        limits.require("refresh", request.getRemoteAddr(), null);
        return result(sessions.refresh(token, requestId, active), response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = "vm_refresh", required = false) String token,
            HttpServletResponse response,
            HttpServletRequest request) {
        sessions.revoke(token);
        cookie(response, "", Duration.ZERO);
        csrf.saveToken(null, request, response);
    }

    @GetMapping("/me")
    public AuthAccounts.Profile me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.profile(Long.parseLong(jwt.getSubject()));
    }

    private Result result(AuthSessions.Grant grant, HttpServletResponse response) {
        var user = accounts.profile(grant.userId());
        var access = tokens.issue(grant);
        cookie(
                response,
                grant.refreshToken(),
                Duration.between(clock.instant(), grant.expiresAt()));
        response.setHeader("Cache-Control", "no-store");
        return new Result(access.value(), access.expiresAt(), user);
    }

    private void cookie(HttpServletResponse response, String value, Duration duration) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from("vm_refresh", value)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite("Lax")
                        .path(cookiePath)
                        .maxAge(duration.isNegative() ? Duration.ZERO : duration)
                        .build()
                        .toString());
    }
}
