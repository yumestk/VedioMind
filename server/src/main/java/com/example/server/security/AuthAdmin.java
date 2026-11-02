package com.example.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/** Offline tool: no Spring application, MQ consumers or HTTP listeners are started. */
public final class AuthAdmin {
    public static void main(String[] args) throws Exception {
        if (args.length == 0)
            throw new IllegalArgumentException(
                    "Use init-key | audit | hash-passwords <new encrypted-backup-file>");
        Path key =
                Path.of(
                        System.getenv()
                                .getOrDefault(
                                        "AUTH_KEY_FILE",
                                        System.getProperty("user.home") + "/.vediomind/auth.key"));
        if ("init-key".equals(args[0])) {
            Files.createDirectories(key.toAbsolutePath().getParent());
            byte[] material = new byte[64];
            new SecureRandom().nextBytes(material);
            Files.createFile(
                    key,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(key, Base64.getEncoder().encodeToString(material));
            System.out.println(
                    "Persistent auth key initialized; existing keys are never overwritten.");
            return;
        }
        if ("backup-db".equals(args[0]) && args.length == 2) {
            Path backup = Path.of(args[1]);
            var crypto = new AuthCrypto(key);
            byte[] dump = System.in.readAllBytes();
            if (dump.length == 0) throw new IllegalArgumentException("Database dump is empty");
            Files.createFile(
                    backup,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(
                    backup,
                    crypto.encrypt(
                            new String(dump, java.nio.charset.StandardCharsets.UTF_8),
                            "database-backup-v1"));
            System.out.println("Encrypted database backup created.");
            return;
        }
        if ("restore-backup-file".equals(args[0]) && args.length == 3) {
            Path output = Path.of(args[2]);
            var crypto = new AuthCrypto(key);
            String plain = crypto.decrypt(Files.readString(Path.of(args[1])), "database-backup-v1");
            Files.createFile(
                    output,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(output, plain);
            System.out.println(
                    "Decrypted backup file created; restore only into an isolated database.");
            return;
        }
        Properties config = new Properties();
        Path properties =
                Path.of(
                        System.getenv()
                                .getOrDefault(
                                        "AUTH_DB_CONFIG",
                                        "src/main/resources/application.properties"));
        if (Files.exists(properties))
            try (var reader = Files.newBufferedReader(properties)) {
                config.load(reader);
            }
        String url = value(config, "AUTH_DB_URL", "spring.datasource.url");
        String user = value(config, "AUTH_DB_USER", "spring.datasource.username");
        String password = value(config, "AUTH_DB_PASSWORD", "spring.datasource.password");
        try (Connection db = DriverManager.getConnection(url, user, password)) {
            db.setAutoCommit(false);
            try {
                int duplicateGroups;
                try (var statement = db.createStatement();
                        var rows =
                                statement.executeQuery(
                                        "SELECT COUNT(*) FROM (SELECT username FROM users GROUP BY"
                                                + " username HAVING COUNT(*)>1) duplicates")) {
                    rows.next();
                    duplicateGroups = rows.getInt(1);
                }
                List<Map<String, Object>> accounts = new ArrayList<>();
                try (var statement = db.createStatement();
                        var rows =
                                statement.executeQuery(
                                        "SELECT id,password FROM users FOR UPDATE")) {
                    while (rows.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rows.getLong(1));
                        row.put("password", rows.getString(2));
                        accounts.add(row);
                    }
                }
                long pending =
                        accounts.stream()
                                .filter(
                                        row ->
                                                !((String) row.get("password"))
                                                        .startsWith("{pbkdf2}"))
                                .count();
                System.out.printf(
                        "accounts=%d, pending_password_hashes=%d, duplicate_username_groups=%d%n",
                        accounts.size(), pending, duplicateGroups);
                if ("audit".equals(args[0])) {
                    db.rollback();
                    return;
                }
                if (!"hash-passwords".equals(args[0]) || args.length != 2)
                    throw new IllegalArgumentException(
                            "Use hash-passwords <new encrypted-backup-file>");
                if (duplicateGroups != 0)
                    throw new IllegalStateException(
                            "Duplicate usernames require manual resolution; no accounts changed");
                if (pending == 0) {
                    db.rollback();
                    System.out.println("No password conversion required.");
                    return;
                }
                var crypto = new AuthCrypto(key);
                Path backup = Path.of(args[1]);
                Files.createFile(
                        backup,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
                Files.writeString(
                        backup,
                        crypto.encrypt(
                                new ObjectMapper().writeValueAsString(accounts),
                                "password-backup-v1"));
                var encoder = new SecurityConfig().passwordEncoder();
                try (var update = db.prepareStatement("UPDATE users SET password=? WHERE id=?")) {
                    for (var row : accounts) {
                        String original = (String) row.get("password");
                        if (original.startsWith("{pbkdf2}")) continue;
                        if (original.startsWith("{"))
                            throw new IllegalStateException(
                                    "Unknown password encoding; transaction rolled back");
                        String encoded = encoder.encode(original);
                        if (!encoder.matches(original, encoded))
                            throw new IllegalStateException(
                                    "Password conversion verification failed");
                        update.setString(1, encoded);
                        update.setLong(2, ((Number) row.get("id")).longValue());
                        update.addBatch();
                    }
                    update.executeBatch();
                }
                db.commit();
                System.out.println(
                        "Password conversion committed; IDs and ownership preserved. Protect the"
                                + " encrypted backup and key separately.");
            } catch (Exception ex) {
                db.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Database operation failed (SQLState="
                            + ex.getSQLState()
                            + "); no credentials printed");
        }
    }

    private static String value(Properties p, String env, String property) {
        String value = System.getenv().getOrDefault(env, p.getProperty(property, ""));
        if (value.isBlank() || value.contains("${"))
            throw new IllegalArgumentException(
                    "Set " + env + " or a literal offline DB configuration");
        return value;
    }
}
