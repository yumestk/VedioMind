package com.example.server.service;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.example.server.config.ObjectStorageConfig;
import com.example.server.controller.MediaUploadController;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaUploadSessionMapper;
import com.example.server.security.CurrentUserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("eval")
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class UploadWeakNetworkEvalTest {
    private static final long PROTECTED_ID = 43;
    private static final int MIB = 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void weakNetworkResumeAndDeduplication() throws Exception {
        var settings = new EvalSettings();
        assertEquals(PROTECTED_ID, settings.mediaId);
        var jdbc = new JdbcTemplate(settings.dataSource());
        String primaryBucket = settings.get("minio.bucketName", "MINIO_BUCKET_NAME", "media");
        String namespace = "upload-eval-" + UUID.randomUUID();
        String bucket = namespace;
        var auth = new FixtureAuth(UUID.randomUUID().toString());
        String endpoint = settings.get("minio.endpoint", "MINIO_ENDPOINT", "http://localhost:9000");
        String access = settings.get("minio.accessKey", "MINIO_ACCESS_KEY", "minioadmin");
        String secret = settings.get("minio.secretKey", "MINIO_SECRET_KEY", "minioadmin");

        var redis = settings.redis();
        try (var protectedS3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            var before = protectedState(jdbc, protectedS3, primaryBucket, settings);
            var initial = counts(jdbc, namespace);
            assertEquals(new Counts(0, 0, 0), initial);
            assertBucketAbsent(protectedS3, bucket);
            var results = new TreeMap<String, Object>();
            results.put("before", before);
            results.put("initial", initial);
            long start = System.nanoTime();
            Path directory = Files.createTempDirectory(namespace);
            boolean bucketCreated = false;
            try {
                Path sample = directory.resolve("sample.mp4");
                generateSample(sample);
                String sourceHash = EvalSettings.fileHash(sample);
                results.put("sourceSha256", sourceHash);
                results.put("sampleBytes", Files.size(sample));
                auth.userA = createUser(jdbc, namespace + "-a");
                auth.userB = createUser(jdbc, namespace + "-b");

                var application = new SpringApplication(UploadApplication.class);
                application.addInitializers(context -> {
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("dataSource", settings.dataSource());
                    beans.registerSingleton("redissonClient", redis);
                    beans.registerSingleton("fixtureAuth", auth);
                });
                // Only upload MVC, storage, Redis, and the SQL mapper factories are loaded.
                bucketCreated = true;
                try (var context = (ServletWebServerApplicationContext) application.run(
                        "--server.address=127.0.0.1", "--server.port=0",
                        "--minio.bucketName=" + bucket, "--minio.endpoint=" + endpoint,
                        "--minio.publicEndpoint=" + endpoint,
                        "--spring.main.banner-mode=off",
                        "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")) {
                    var storage = context.getBean(ObjectStorageService.class);
                    var s3 = context.getBean(S3Client.class);
                    var port = context.getWebServer().getPort();
                    String scenarioAKey = null;
                    try {
                        var scenarioA = runClient(directory, sample, port, "A", auth.userA, auth);
                        results.put("A", scenarioA);
                        scenarioAKey = keyFor(jdbc, scenarioA.path("sessionId").asText());
                        long readbackStart = System.nanoTime();
                        verifyObject(jdbc, storage, s3, bucket, scenarioA, sourceHash, auth.userA);
                        results.put("readbackAMs", elapsedMs(readbackStart));
                    } finally {
                        if (scenarioAKey != null) storage.remove(scenarioAKey);
                    }

                    var scenarioB = runClient(directory, sample, port, "B", auth.userB, auth);
                    results.put("B", scenarioB);
                    long readbackStart = System.nanoTime();
                    String originalKey = verifyObject(jdbc, storage, s3, bucket, scenarioB, sourceHash, auth.userB);
                    results.put("readbackBMs", elapsedMs(readbackStart));
                    var sampleObjectsBefore = sampleObjects(s3, bucket);
                    assertEquals(List.of(originalKey), sampleObjectsBefore);

                    var scenarioC = runClient(directory, sample, port, "C", auth.userB, auth);
                    results.put("C", scenarioC);
                    assertEquals(scenarioB.path("mediaId"), scenarioC.path("mediaId"));
                    String duplicateKey = keyFor(jdbc, scenarioC.path("sessionId").asText());
                    assertNotEquals(originalKey, duplicateKey);
                    assertNull(storage.objectSize(duplicateKey));
                    var sampleObjectsAfter = sampleObjects(s3, bucket);
                    assertEquals(sampleObjectsBefore, sampleObjectsAfter);
                    assertEquals(new Counts(2, 2, 3), counts(jdbc, namespace));
                    assertTrue(s3.listMultipartUploads(r -> r.bucket(bucket)).uploads().isEmpty());
                    results.put("dedupObjectsBefore", sampleObjectsBefore.size());
                    results.put("dedupObjectsAfter", sampleObjectsAfter.size());
                    results.put("duplicateKey", duplicateKey);
                    results.put("during", counts(jdbc, namespace));
                }
            } finally {
                boolean cleanupBucket = bucketCreated;
                assertAll("independent cleanup",
                        () -> { if (cleanupBucket) cleanBucket(protectedS3, bucket); },
                        () -> cleanUsers(jdbc, namespace, auth.userA, auth.userB),
                        () -> deleteDirectory(directory),
                        () -> {
                            for (long user : List.of(auth.userA, auth.userB)) {
                                if (user == 0) continue;
                                assertFalse(redis.getBucket(MediaService.MEDIA_LIST_CACHE_PREFIX + user).isExists());
                                assertEquals(0, redis.getKeys().countExists("lock:media-content:" + user
                                        + ":" + results.get("sourceSha256")));
                            }
                        });
                assertEquals(new Counts(0, 0, 0), counts(jdbc, namespace));
                assertBucketAbsent(protectedS3, bucket);
                assertTrue(Files.notExists(directory));
                var after = protectedState(jdbc, protectedS3, primaryBucket, settings);
                assertEquals(before, after, "protected media must remain identical");
                results.put("after", after);
                results.put("cleanup", counts(jdbc, namespace));
                results.put("bucketRemoved", true);
                results.put("tempRemoved", true);
            }
            results.put("wallMs", elapsedMs(start));
            EvalSettings.write("upload-result.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        } finally {
            redis.shutdown();
        }
    }

    private void generateSample(Path sample) throws Exception {
        var random = new Random(43);
        byte[] buffer = new byte[MIB];
        try (var output = Files.newOutputStream(sample)) {
            for (int i = 0; i < 130; i++) {
                random.nextBytes(buffer);
                output.write(buffer);
            }
        }
    }

    private JsonNode runClient(Path dir, Path sample, int port, String scenario, long user, FixtureAuth auth)
            throws Exception {
        var log = dir.resolve(scenario + ".log");
        var builder = new ProcessBuilder("node", "src/test/resources/eval/upload-eval.mjs",
                "http://127.0.0.1:" + port, sample.toString(), scenario, Long.toString(user));
        builder.environment().put("UPLOAD_EVAL_TOKEN", auth.token);
        var child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "upload client timed out for " + scenario);
            String output = Files.readString(log);
            assertEquals(0, child.exitValue(), () -> "upload client failed for " + scenario + "\n"
                    + String.join("\n", output.lines().filter(line -> line.startsWith("AssertionError")
                            || line.startsWith("Error:") || line.contains("at file:") && line.contains("upload-eval.mjs")).toList()));
            return json.readTree(output.lines().filter(line -> line.startsWith("{")).reduce((a, b) -> b).orElseThrow());
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
        }
    }

    private String verifyObject(JdbcTemplate jdbc, ObjectStorageService storage, S3Client s3, String bucket,
                                JsonNode result, String sourceHash, long user) {
        long id = result.path("mediaId").asLong();
        assertNotEquals(PROTECTED_ID, id);
        String key = keyFor(jdbc, result.path("sessionId").asText());
        assertEquals(130L * MIB, s3.headObject(r -> r.bucket(bucket).key(key)).contentLength());
        assertEquals(sourceHash, storage.calculateSha256(key));
        assertEquals(sourceHash, jdbc.queryForObject(
                "SELECT content_sha256 FROM media_files WHERE id=? AND user_id=? AND object_key=?",
                String.class, id, user, key));
        return key;
    }

    private String keyFor(JdbcTemplate jdbc, String sessionId) {
        return jdbc.queryForObject("SELECT object_key FROM media_upload_sessions WHERE id=?", String.class, sessionId);
    }

    private List<String> sampleObjects(S3Client s3, String bucket) {
        return s3.listObjectsV2(r -> r.bucket(bucket)).contents().stream().map(o -> o.key()).toList();
    }

    private long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private long createUser(JdbcTemplate jdbc, String name) {
        String passwordHash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode(UUID.randomUUID().toString());
        jdbc.update("INSERT INTO users (username, password, role) VALUES (?, ?, 'USER')", name, passwordHash);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
    }

    private Counts counts(JdbcTemplate jdbc, String namespace) {
        String users = "SELECT id FROM users WHERE username IN (?, ?)";
        Object[] names = {namespace + "-a", namespace + "-b"};
        return new Counts(
                jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username IN (?, ?)", Integer.class, names),
                jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE user_id IN (" + users + ")", Integer.class, names),
                jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_sessions WHERE user_id IN (" + users + ")",
                        Integer.class, names));
    }

    private void cleanUsers(JdbcTemplate jdbc, String namespace, long userA, long userB) {
        Object[] names = {namespace + "-a", namespace + "-b"};
        jdbc.update("DELETE FROM media_upload_sessions WHERE user_id IN (SELECT id FROM users WHERE username IN (?, ?))", names);
        jdbc.update("DELETE FROM media_files WHERE user_id IN (SELECT id FROM users WHERE username IN (?, ?))", names);
        jdbc.update("DELETE FROM users WHERE username IN (?, ?)", names);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE user_id IN (?, ?)",
                Integer.class, userA, userB));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_sessions WHERE user_id IN (?, ?)",
                Integer.class, userA, userB));
    }

    private void cleanBucket(S3Client s3, String bucket) {
        try {
            for (var upload : s3.listMultipartUploads(r -> r.bucket(bucket)).uploads()) {
                s3.abortMultipartUpload(r -> r.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()));
            }
            for (var item : s3.listObjectsV2(r -> r.bucket(bucket)).contents()) {
                s3.deleteObject(r -> r.bucket(bucket).key(item.key()));
            }
            assertTrue(s3.listObjectsV2(r -> r.bucket(bucket)).contents().isEmpty());
            assertTrue(s3.listMultipartUploads(r -> r.bucket(bucket)).uploads().isEmpty());
            s3.deleteBucket(r -> r.bucket(bucket));
        } catch (S3Exception error) {
            if (error.statusCode() != 404) throw error;
        }
    }

    private void assertBucketAbsent(S3Client s3, String bucket) {
        assertEquals(404, assertThrows(S3Exception.class, () -> s3.headBucket(r -> r.bucket(bucket))).statusCode());
    }

    private void deleteDirectory(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) Files.delete(file);
        }
        Files.delete(directory);
    }

    private ProtectedState protectedState(JdbcTemplate jdbc, S3Client s3, String bucket, EvalSettings settings)
            throws Exception {
        Map<String, List<String>> rows = new TreeMap<>();
        for (String table : List.of("media_files", "media_upload_sessions", "transcript_segments", "video_chapters",
                "media_visual_evidence", "video_keyframes", "video_knowledge_chunks", "analysis_jobs")) {
            String column = table.equals("media_files") ? "id" : "media_id";
            var records = new ArrayList<String>();
            for (var row : jdbc.queryForList("SELECT * FROM " + table + " WHERE " + column + "=?", PROTECTED_ID)) {
                records.add(json.writeValueAsString(new TreeMap<>(row)));
            }
            records.sort(String::compareTo);
            rows.put(table, records);
        }
        String key = jdbc.queryForObject("SELECT object_key FROM media_files WHERE id=?", String.class, PROTECTED_ID);
        var head = s3.headObject(r -> r.bucket(bucket).key(key));
        String objectHash = sha(List.of(key, head.contentLength(), head.eTag(), head.lastModified().toString(),
                new TreeMap<>(head.metadata())));

        String qdrant = settings.get("eval.qdrantHttp", "QDRANT_HTTP_URL", "http://127.0.0.1:6333");
        String collection = settings.get("qdrant.collection", "QDRANT_COLLECTION", "video_knowledge_chunks_v1");
        var points = new ArrayList<JsonNode>();
        JsonNode offset = null;
        do {
            var body = json.createObjectNode();
            body.put("limit", 100).put("with_payload", true).put("with_vector", true);
            body.putObject("filter").putArray("must").addObject().put("key", "media_id")
                    .putObject("match").put("value", PROTECTED_ID);
            if (offset != null) body.set("offset", offset);
            var request = HttpRequest.newBuilder(URI.create(qdrant + "/collections/" + collection + "/points/scroll"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            String apiKey = settings.get("qdrant.api-key", "QDRANT_API_KEY", "");
            if (!apiKey.isBlank()) request.header("api-key", apiKey);
            var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var result = json.readTree(response.body()).required("result");
            result.required("points").forEach(points::add);
            offset = result.get("next_page_offset");
        } while (offset != null && !offset.isNull());
        points.sort(Comparator.comparing(point -> point.path("id").asText()));
        assertFalse(points.isEmpty());
        var rowCounts = new TreeMap<String, Integer>();
        rows.forEach((name, records) -> rowCounts.put(name, records.size()));
        return new ProtectedState(rowCounts, sha(rows), points.size(), sha(points), head.contentLength(), objectHash);
    }

    private String sha(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
    }

    private record Counts(int users, int media, int sessions) {}
    private record ProtectedState(Map<String, Integer> sqlCounts, String sqlSha256, int qdrantPoints,
                                  String qdrantSha256, long minioBytes, String minioMetadataSha256) {}

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
            RedisAutoConfiguration.class})
    @MapperScan(basePackageClasses = MediaFileMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
    @Import({ObjectStorageConfig.class, ObjectStorageService.class, MediaUploadService.class, MediaUploadController.class})
    static class UploadApplication {
        @Bean
        MediaService mediaService(MediaFileMapper mapper, ObjectStorageService storage,
                                  StringRedisTemplate cache, ObjectMapper json, RedissonClient redis) {
            return new MediaService(mapper, storage, null, cache, json, null, null, null, redis, null, null);
        }
    }

    // Replace the auth boundary only. Accept requests for the two synthetic users using a per-run token.
    @RestController
    static class FixtureAuth implements WebMvcConfigurer {
        final String token;
        long userA;
        long userB;

        FixtureAuth(String token) {
            this.token = token;
        }

        @GetMapping("/user/csrf")
        Map<String, String> csrf() {
            return Map.of("token", token);
        }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(CurrentUserId.class);
                }

                @Override
                public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer model,
                                              NativeWebRequest request, WebDataBinderFactory binder) {
                    String authorization = request.getHeader("Authorization");
                    if (("Bearer " + token + ":" + userA).equals(authorization)) return userA;
                    if (("Bearer " + token + ":" + userB).equals(authorization)) return userB;
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                }
            });
        }
    }
}
