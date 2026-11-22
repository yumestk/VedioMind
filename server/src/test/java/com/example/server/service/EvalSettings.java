package com.example.server.service;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

final class EvalSettings {
    final long mediaId = Long.parseLong(requiredProperty("eval.mediaId"));
    private final Properties properties = new Properties();

    EvalSettings() throws IOException {
        var local = Path.of("src/main/resources/application.properties");
        if (Files.isRegularFile(local)) {
            try (var reader = Files.newBufferedReader(local)) {
                properties.load(reader);
            }
        }
    }

    String get(String property, String env, String fallback) {
        String environment = System.getenv(env);
        return System.getProperty(property, environment == null || environment.isBlank()
                ? properties.getProperty(property, fallback) : environment);
    }

    String aliyunKey() {
        String value = get("ai.aliyun.api-key", "ALIYUN_API_KEY", "");
        if (value.isBlank() || value.startsWith("${")) throw new IllegalStateException("Set ALIYUN_API_KEY");
        return value;
    }

    DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                get("spring.datasource.url", "DB_URL", "jdbc:mysql://127.0.0.1:3307/media_db"),
                get("spring.datasource.username", "DB_USERNAME", "root"),
                get("spring.datasource.password", "DB_PASSWORD", "root"));
    }

    RedissonClient redis() {
        var config = new Config();
        var server = config.useSingleServer()
                .setAddress("redis://" + get("spring.data.redis.host", "REDIS_HOST", "localhost")
                        + ":" + get("spring.data.redis.port", "REDIS_PORT", "6379"))
                .setDatabase(Integer.parseInt(get("spring.data.redis.database", "REDIS_DATABASE", "0")));
        String password = get("spring.data.redis.password", "REDIS_PASSWORD", "");
        if (!password.isBlank()) server.setPassword(password);
        return Redisson.create(config);
    }

    void verifyVideo(Path video) throws Exception {
        String expected = new JdbcTemplate(dataSource()).queryForObject(
                "SELECT content_sha256 FROM media_files WHERE id=?", String.class, mediaId);
        if (expected != null && !expected.equals(fileHash(video))) {
            throw new IllegalArgumentException("Video SHA-256 does not match mediaId " + mediaId);
        }
    }

    static String fileHash(Path file) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(file), sha)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Specify -D" + name);
        return value;
    }

    static String percent(double numerator, double denominator) {
        return denominator == 0 ? "N/A" : String.format(Locale.ROOT, "%.2f%%", 100 * numerator / denominator);
    }

    static void write(String name, String report) throws IOException {
        var dir = Files.createDirectories(Path.of("target", "eval"));
        Files.writeString(dir.resolve(name), report);
    }
}
