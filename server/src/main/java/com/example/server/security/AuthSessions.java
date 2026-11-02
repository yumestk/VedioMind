package com.example.server.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthSessions {
    private static final Duration IDLE = Duration.ofDays(30);
    private static final Duration RETRY = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuthCrypto crypto;

    public record Session(
            String id,
            long userId,
            String tokenHash,
            long generation,
            Instant expiresAt,
            Instant revokedAt) {}

    public record Grant(String sessionId, long userId, String refreshToken, Instant expiresAt) {}

    public AuthSessions(JdbcTemplate jdbc, Clock clock, AuthCrypto crypto) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.crypto = crypto;
    }

    @Transactional
    public Grant create(long userId) {
        String id = UUID.randomUUID().toString(), token = id + "." + AuthCrypto.randomToken();
        Instant now = clock.instant(), expiry = now.plus(IDLE);
        jdbc.update(
                "INSERT INTO"
                    + " auth_sessions(id,user_id,token_hash,generation,created_at,last_active_at,expires_at)"
                    + " VALUES(?,?,?,0,?,?,?)",
                id,
                userId,
                AuthCrypto.hash(token),
                ts(now),
                ts(now),
                ts(expiry));
        return new Grant(id, userId, token, expiry);
    }

    @Transactional
    public Grant refresh(String token, String requestId, boolean active) {
        String id = sessionId(token);
        validateId(requestId);
        Session s = locked(id);
        Instant now = clock.instant();
        if (s == null || s.revokedAt() != null || !now.isBefore(s.expiresAt())) throw expired();
        String digest = AuthCrypto.hash(token);
        var receipts =
                jdbc.queryForList(
                        "SELECT consumed_hash,generation,result_ciphertext,valid_until FROM"
                                + " auth_refresh_receipts WHERE session_id=? AND request_id=?",
                        id,
                        requestId);
        if (!receipts.isEmpty()) {
            var r = receipts.getFirst();
            if (((Timestamp) r.get("valid_until")).toInstant().isAfter(now)
                    && ((Number) r.get("generation")).longValue() == s.generation()
                    && (digest.equals(r.get("consumed_hash")) || digest.equals(s.tokenHash()))) {
                return new Grant(
                        id,
                        s.userId(),
                        crypto.decrypt((String) r.get("result_ciphertext"), id + requestId),
                        s.expiresAt());
            }
            throw new AuthException(409, "REFRESH_RETRY_EXPIRED", "刷新请求已过期，请重试");
        }
        if (!digest.equals(s.tokenHash())) {
            Integer recent =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM auth_refresh_receipts WHERE session_id=? AND"
                                    + " consumed_hash=? AND valid_until>?",
                            Integer.class,
                            id,
                            digest,
                            ts(now));
            if (recent != null && recent > 0)
                throw new AuthException(409, "REFRESH_CONFLICT", "登录状态正在刷新，请重试");
            throw expired();
        }
        String next = id + "." + AuthCrypto.randomToken();
        Instant expiry = active ? now.plus(IDLE) : s.expiresAt();
        jdbc.update(
                "UPDATE auth_sessions SET token_hash=?,generation=generation+1,last_active_at=CASE"
                        + " WHEN ? THEN ? ELSE last_active_at END,expires_at=? WHERE id=?",
                AuthCrypto.hash(next),
                active,
                ts(now),
                ts(expiry),
                id);
        jdbc.update(
                "INSERT INTO"
                    + " auth_refresh_receipts(session_id,request_id,consumed_hash,generation,result_ciphertext,valid_until)"
                    + " VALUES(?,?,?,?,?,?)",
                id,
                requestId,
                digest,
                s.generation() + 1,
                crypto.encrypt(next, id + requestId),
                ts(now.plus(RETRY)));
        return new Grant(id, s.userId(), next, expiry);
    }

    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        String id;
        try {
            id = sessionId(token);
        } catch (AuthException ignored) {
            return;
        }
        Session s = locked(id);
        if (s == null || s.revokedAt() != null) return;
        String digest = AuthCrypto.hash(token);
        boolean matches =
                digest.equals(s.tokenHash())
                        || Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*)>0 FROM auth_refresh_receipts WHERE"
                                            + " session_id=? AND consumed_hash=? AND valid_until>?",
                                        Boolean.class,
                                        id,
                                        digest,
                                        ts(clock.instant())));
        if (matches) {
            jdbc.update(
                    "UPDATE auth_sessions SET revoked_at=? WHERE id=?", ts(clock.instant()), id);
            jdbc.update("DELETE FROM auth_refresh_receipts WHERE session_id=?", id);
        }
    }

    private Session locked(String id) {
        var rows =
                jdbc.query(
                        "SELECT id,user_id,token_hash,generation,expires_at,revoked_at FROM"
                                + " auth_sessions WHERE id=? FOR UPDATE",
                        (r, n) ->
                                new Session(
                                        r.getString(1),
                                        r.getLong(2),
                                        r.getString(3),
                                        r.getLong(4),
                                        r.getTimestamp(5).toInstant(),
                                        r.getTimestamp(6) == null
                                                ? null
                                                : r.getTimestamp(6).toInstant()),
                        id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String sessionId(String token) {
        if (token == null
                || token.length() > 160
                || !token.matches("[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")) throw expired();
        String id = token.substring(0, 36);
        validateId(id);
        return id;
    }

    private void validateId(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new AuthException(400, "INVALID_REFRESH_REQUEST", "无效的刷新请求标识");
        }
    }

    private AuthException expired() {
        return new AuthException(401, "SESSION_EXPIRED", "登录已失效，请重新登录");
    }

    private Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    @Scheduled(fixedDelayString = "${auth.cleanup-ms:60000}")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        jdbc.update("DELETE FROM auth_refresh_receipts WHERE valid_until<?", ts(now));
        jdbc.update(
                "DELETE FROM auth_sessions WHERE expires_at<? OR revoked_at<?",
                ts(now),
                ts(now.minus(Duration.ofDays(1))));
    }
}
