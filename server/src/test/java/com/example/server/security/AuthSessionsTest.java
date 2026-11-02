package com.example.server.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class AuthSessionsTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    JdbcTemplate jdbc;
    AuthSessions sessions;
    TransactionTemplate tx;
    MutableClock clock;
    AuthCrypto crypto;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("AUTH_TEST_MYSQL_URL");
        if (url != null
                && !url.matches("jdbc:mysql://[^/]+/vediomind_auth_test_[A-Za-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException(
                    "An isolated ved iomind auth test database is required");
        var ds =
                new DriverManagerDataSource(
                        url == null
                                ? "jdbc:h2:mem:"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1"
                                : url,
                        url == null ? "sa" : System.getenv("AUTH_TEST_MYSQL_USER"),
                        url == null ? "" : System.getenv("AUTH_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS auth_refresh_receipts");
        jdbc.execute("DROP TABLE IF EXISTS auth_sessions");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute(
                "CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(100), password"
                        + " VARCHAR(255))");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V18__add_auth_sessions.sql"))
                .execute(ds);
        jdbc.update("INSERT INTO users VALUES(1,'alice','unused')");
        Path key = Files.createTempFile("vm-auth-test-", ".key");
        Files.writeString(key, Base64.getEncoder().encodeToString(new byte[64]));
        crypto = new AuthCrypto(key);
        Files.delete(key);
        clock = new MutableClock();
        sessions = new AuthSessions(jdbc, clock, crypto);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    AuthSessions.Grant create() {
        return tx.execute(s -> sessions.create(1));
    }

    AuthSessions.Grant refresh(String token, String id, boolean active) {
        return tx.execute(s -> sessions.refresh(token, id, active));
    }

    @Test
    void rotatingRetryRecoversOnlySameBoundedResult() {
        var first = create();
        String request = UUID.randomUUID().toString();
        var next = refresh(first.refreshToken(), request, true);
        assertThat(next.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(refresh(first.refreshToken(), request, true).refreshToken())
                .isEqualTo(next.refreshToken());
        assertThat(refresh(next.refreshToken(), request, true).refreshToken())
                .isEqualTo(next.refreshToken());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_ciphertext FROM auth_refresh_receipts",
                                String.class))
                .doesNotContain(next.refreshToken());
        assertThatThrownBy(() -> refresh(first.refreshToken(), UUID.randomUUID().toString(), true))
                .isInstanceOfSatisfying(
                        AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        clock.advance(Duration.ofSeconds(31));
        assertThatThrownBy(() -> refresh(first.refreshToken(), UUID.randomUUID().toString(), true))
                .isInstanceOfSatisfying(
                        AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
        assertThat(refresh(next.refreshToken(), UUID.randomUUID().toString(), true)).isNotNull();
    }

    @Test
    void concurrentSameRequestHasOneRotation() throws Exception {
        var first = create();
        String request = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<AuthSessions.Grant> task =
                    () -> {
                        start.await();
                        return refresh(first.refreshToken(), request, true);
                    };
            var a = pool.submit(task);
            var b = pool.submit(task);
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).refreshToken())
                    .isEqualTo(b.get(10, TimeUnit.SECONDS).refreshToken());
        }
        assertThat(jdbc.queryForObject("SELECT generation FROM auth_sessions", Long.class))
                .isEqualTo(1);
    }

    @Test
    void backgroundRefreshDoesNotKeepIdleUserAlive() {
        var first = create();
        clock.advance(Duration.ofDays(29));
        var background = refresh(first.refreshToken(), UUID.randomUUID().toString(), false);
        assertThat(background.expiresAt()).isEqualTo(first.expiresAt());
        clock.advance(Duration.ofDays(1));
        assertThatThrownBy(
                        () ->
                                refresh(
                                        background.refreshToken(),
                                        UUID.randomUUID().toString(),
                                        false))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void activeRefreshSlidesExpiryAndSurvivesServiceRestart() {
        var first = create();
        clock.advance(Duration.ofDays(29));
        var active = refresh(first.refreshToken(), UUID.randomUUID().toString(), true);
        assertThat(active.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        sessions = new AuthSessions(jdbc, clock, crypto);
        assertThat(refresh(active.refreshToken(), UUID.randomUUID().toString(), false)).isNotNull();
    }

    @Test
    void logoutRevokesPersistentSessionAndIsIdempotent() {
        var first = create();
        tx.executeWithoutResult(s -> sessions.revoke(first.refreshToken()));
        assertThatThrownBy(() -> refresh(first.refreshToken(), UUID.randomUUID().toString(), true))
                .isInstanceOf(AuthException.class);
        tx.executeWithoutResult(s -> sessions.revoke(first.refreshToken()));
    }

    @Test
    void failedReceiptWriteRollsBackRotation() {
        var first = create();
        jdbc.execute("DROP TABLE auth_refresh_receipts");
        assertThatThrownBy(() -> refresh(first.refreshToken(), UUID.randomUUID().toString(), true))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_sessions", String.class))
                .isEqualTo(AuthCrypto.hash(first.refreshToken()));
    }

    @Test
    void cleanupRemovesExpiredSessionsAndReceipts() {
        var first = create();
        refresh(first.refreshToken(), UUID.randomUUID().toString(), false);
        clock.advance(Duration.ofDays(31));
        tx.executeWithoutResult(s -> sessions.cleanup());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_receipts", Integer.class))
                .isZero();
    }

    @Test
    void sameUsernameIsUniqueUnderConcurrentInsert() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> insert =
                    () -> {
                        start.await();
                        try {
                            jdbc.update(
                                    "INSERT INTO users VALUES(?,?,?)",
                                    Thread.currentThread().threadId() + 100,
                                    "same_name",
                                    "unused");
                            return true;
                        } catch (org.springframework.dao.DuplicateKeyException e) {
                            return false;
                        }
                    };
            var a = pool.submit(insert);
            var b = pool.submit(insert);
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        }
    }
}
