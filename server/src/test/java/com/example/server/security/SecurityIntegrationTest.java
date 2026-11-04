package com.example.server.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.example.server.controller.*;
import com.example.server.service.*;
import com.fasterxml.jackson.databind.*;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@SpringJUnitConfig(SecurityIntegrationTest.Config.class)
@WebAppConfiguration
class SecurityIntegrationTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement
    @Import({
        SecurityConfig.class,
        AuthAccounts.class,
        AuthSessions.class,
        AuthTokens.class,
        UserController.class,
        AuthExceptionHandler.class,
        MediaController.class,
        AnalysisController.class,
        MediaUploadController.class,
        AssistantController.class,
        VideoConversationController.class,
        VideoAgentController.class,
        KnowledgeIndexController.class
    })
    static class Config {
        @Bean
        static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties()
                throws Exception {
            var p = new Properties();
            Path key = Files.createTempFile("vm-web-auth-", ".key");
            Files.writeString(key, Base64.getEncoder().encodeToString(new byte[64]));
            key.toFile().deleteOnExit();
            p.setProperty("auth.key-file", key.toString());
            var c = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            c.setProperties(p);
            return c;
        }

        @Bean
        javax.sql.DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:web-auth;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        JdbcTemplate jdbc(javax.sql.DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager(
                javax.sql.DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        AuthRateLimits limits() {
            return mock(AuthRateLimits.class);
        }

        @Bean
        MediaService media() {
            return mock(MediaService.class);
        }

        @Bean
        AnalysisSubmissionService submission() {
            return mock(AnalysisSubmissionService.class);
        }

        @Bean
        MediaUploadService uploads() {
            return mock(MediaUploadService.class);
        }

        @Bean
        AssistantDispatchService assistant() {
            return mock(AssistantDispatchService.class);
        }

        @Bean
        VideoConversationService conversations() {
            return mock(VideoConversationService.class);
        }

        @Bean
        VideoAgentRunService runs() {
            return mock(VideoAgentRunService.class);
        }

        @Bean
        VideoKnowledgeIndexService index() {
            return mock(VideoKnowledgeIndexService.class);
        }

        @Bean
        KnowledgeIndexPreparationService preparation() {
            return mock(KnowledgeIndexPreparationService.class);
        }

        @Bean
        com.example.server.service.ai.AiRateLimitService aiLimits() {
            return mock(com.example.server.service.ai.AiRateLimitService.class);
        }

        @Bean
        AnalysisJobService jobs() {
            return mock(AnalysisJobService.class);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired AuthRateLimits limits;
    @Autowired AuthTokens tokens;
    @Autowired AuthSessions sessions;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired AuthCrypto crypto;
    @Autowired MediaService media;
    @Autowired AnalysisSubmissionService submission;
    @Autowired AnalysisJobService jobs;
    @Autowired ObjectMapper json;
    @Autowired MediaUploadService uploads;
    @Autowired AssistantDispatchService assistant;
    @Autowired VideoConversationService conversations;
    @Autowired VideoAgentRunService runs;
    @Autowired VideoKnowledgeIndexService index;
    @Autowired KnowledgeIndexPreparationService preparation;
    @Autowired com.example.server.service.ai.AiRateLimitService aiLimits;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc.execute("DROP TABLE IF EXISTS auth_refresh_receipts");
        jdbc.execute("DROP TABLE IF EXISTS auth_sessions");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute(
                "CREATE TABLE users(id BIGINT AUTO_INCREMENT PRIMARY KEY,username"
                        + " VARCHAR(100),password VARCHAR(255),nickname VARCHAR(100),avatar"
                        + " VARCHAR(500),role VARCHAR(50))");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V18__add_auth_sessions.sql"))
                .execute(jdbc.getDataSource());
        jdbc.update(
                "INSERT INTO users(id,username,password,role) VALUES(1,'alice',?,'USER')",
                passwords.encode("correct-password"));
        reset(
                limits,
                media,
                submission,
                jobs,
                uploads,
                assistant,
                conversations,
                runs,
                index,
                preparation,
                aiLimits);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor csrfToken() throws Exception {
        var bootstrap = mvc.perform(get("/user/csrf")).andReturn();
        String value =
                json.readTree(bootstrap.getResponse().getContentAsString()).get("token").asText();
        return request -> {
            var cookies = new ArrayList<Cookie>();
            if (request.getCookies() != null) cookies.addAll(List.of(request.getCookies()));
            cookies.add(new Cookie("XSRF-TOKEN", value));
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", value);
            return request;
        };
    }

    String access() {
        return tokens.issue(sessions.create(1)).value();
    }

    @Test
    void loginValidatesHashAndReturnsOnlySafeProfileAndSecureCookie() throws Exception {
        var result =
                mvc.perform(
                                post("/user/login")
                                        .with(csrfToken())
                                        .contentType("application/json")
                                        .content(
                                                "{\"username\":\"alice\",\"password\":\"correct-password\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.userInfo.id").value(1))
                        .andExpect(jsonPath("$.userInfo.password").doesNotExist())
                        .andExpect(
                                header().string(
                                                "Set-Cookie",
                                                org.hamcrest.Matchers.allOf(
                                                        org.hamcrest.Matchers.containsString(
                                                                "HttpOnly"),
                                                        org.hamcrest.Matchers.containsString(
                                                                "Secure"),
                                                        org.hamcrest.Matchers.containsString(
                                                                "SameSite=Lax"),
                                                        org.hamcrest.Matchers.containsString(
                                                                "Path=/api/user"))))
                        .andReturn();
        String jwt =
                json.readTree(result.getResponse().getContentAsString())
                        .get("accessToken")
                        .asText();
        assertThat(decoder.decode(jwt).getSubject()).isEqualTo("1");
        mvc.perform(get("/user/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(
                        post("/user/login")
                                .with(csrfToken())
                                .contentType("application/json")
                                .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void registrationIgnoresInjectedRoleAndHasDatabaseUniqueness() throws Exception {
        String body =
                "{\"username\":\"new_user\",\"password\":\"correct-password\",\"role\":\"ADMIN\",\"id\":1}";
        mvc.perform(
                        post("/user/register")
                                .with(csrfToken())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isCreated());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT role FROM users WHERE username='new_user'", String.class))
                .isEqualTo("USER");
        assertThat(
                        passwords.matches(
                                "correct-password",
                                jdbc.queryForObject(
                                        "SELECT password FROM users WHERE username='new_user'",
                                        String.class)))
                .isTrue();
        mvc.perform(
                        post("/user/register")
                                .with(csrfToken())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/user/register")
                                .with(csrfToken())
                                .contentType("application/json")
                                .content("{\"username\":\"x\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnonymousForgeryAndNonAllowedOriginBeforeBusiness() throws Exception {
        mvc.perform(get("/media").param("userId", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/media").header("Authorization", "Bearer user_1"))
                .andExpect(status().isUnauthorized());
        for (String path :
                List.of("/user/login", "/user/register", "/user/refresh", "/user/logout"))
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        mvc.perform(post("/user/logout").with(csrfToken()).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(media, submission, limits);
    }

    @Test
    void csrfBootstrapWorksWithoutHttpSession() throws Exception {
        var response = mvc.perform(get("/user/csrf")).andExpect(status().isOk()).andReturn();
        String csrf =
                json.readTree(response.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(
                        post("/user/logout")
                                .cookie(new Cookie("XSRF-TOKEN", csrf))
                                .header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isNoContent());
        assertThat(response.getRequest().getSession(false)).isNull();
    }

    @Test
    void statelessBearerRequestsDoNotInvalidateTheBrowserCsrfCookie() throws Exception {
        mvc.perform(get("/user/me").header("Authorization", "Bearer " + access()).with(csrfToken()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void queryUserIdNeverOverridesSignedPrincipal() throws Exception {
        mvc.perform(
                        get("/media")
                                .param("userId", "999")
                                .header("Authorization", "Bearer " + access()))
                .andExpect(status().isOk());
        verify(media).list(1L);
        verify(media, never()).list(999L);
    }

    @Test
    void taskReadsCheckOwnerBeforeReturningData() throws Exception {
        var job = new com.example.server.entity.AnalysisJob();
        job.setId("job-other");
        job.setMediaId(99L);
        when(jobs.findById("job-other")).thenReturn(Optional.of(job));
        doThrow(new IllegalArgumentException("not found")).when(media).requireOwnership(99L, 1L);
        String jwt = access();
        mvc.perform(get("/analysis/jobs/job-other").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
        mvc.perform(get("/analysis/media/99/active-job").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
        verify(jobs, never()).findLatestActive(any());
        verifyNoInteractions(submission);
    }

    @Test
    void jwtRemainsLocallyValidWhenRefreshStoreFailsAndLogoutCannotFakeSuccess() throws Exception {
        var grant = sessions.create(1);
        String token = tokens.issue(grant).value();
        jdbc.execute("ALTER TABLE auth_sessions RENAME TO temporarily_unavailable");
        try {
            mvc.perform(get("/media").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/user/refresh")
                                    .with(csrfToken())
                                    .cookie(new Cookie("vm_refresh", grant.refreshToken()))
                                    .header("X-Refresh-Request", UUID.randomUUID()))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(
                            post("/user/logout")
                                    .with(csrfToken())
                                    .cookie(new Cookie("vm_refresh", grant.refreshToken())))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().doesNotExist("Set-Cookie"));
        } finally {
            jdbc.execute("ALTER TABLE temporarily_unavailable RENAME TO auth_sessions");
        }
        mvc.perform(
                        post("/user/refresh")
                                .with(csrfToken())
                                .cookie(new Cookie("vm_refresh", grant.refreshToken()))
                                .header("X-Refresh-Request", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    void everyResourceEntryUsesSignedIdentityAndRejectsOtherUsersResources() throws Exception {
        String token = access();
        var missing = new IllegalArgumentException("not found");
        when(media.getDetail(99L, 1L, null)).thenThrow(missing);
        when(media.getTranscript(99L, 1L)).thenThrow(missing);
        when(media.getChapters(99L, 1L)).thenThrow(missing);
        doThrow(missing).when(media).delete(99L, 1L);
        doThrow(missing).when(media).requireOwnership(99L, 1L);
        when(submission.submit(99L, 1L)).thenThrow(missing);
        when(uploads.get("other", 1L)).thenThrow(new NoSuchElementException());
        when(uploads.createPartUrl("other", 1, 1L)).thenThrow(new NoSuchElementException());
        when(uploads.complete(eq("other"), any(), eq(1L))).thenThrow(new NoSuchElementException());
        doThrow(new NoSuchElementException()).when(uploads).abort("other", 1L);
        when(conversations.list(99L, 1L, null)).thenThrow(new NoSuchElementException());
        when(conversations.messages(999L, 1L)).thenThrow(new NoSuchElementException());
        when(assistant.dispatch(eq(99L), eq(1L), any())).thenThrow(new NoSuchElementException());
        var run = new com.example.server.entity.VideoAgentRun();
        run.setId("other");
        run.setMediaId(99L);
        when(runs.getOwned("other", 1L)).thenThrow(new NoSuchElementException());
        var requests =
                List.of(
                        get("/media/99"),
                        get("/media/99/transcript"),
                        get("/media/99/chapters"),
                        delete("/media/99"),
                        get("/media/uploads/other"),
                        post("/media/uploads/other/parts/1/url"),
                        post("/media/uploads/other/complete").content("{\"parts\":[]}"),
                        delete("/media/uploads/other"),
                        get("/media/99/conversations"),
                        get("/conversations/999/messages"),
                        get("/agent-runs/other"),
                        post("/analysis/media/99"),
                        post("/media/99/assistant/dispatch").content("{\"content\":\"hello\"}"),
                        post("/media/99/knowledge-index"),
                        get("/media/99/knowledge-index/latest-job"),
                        get("/media/99/knowledge-index/jobs/other"));
        for (var request : requests)
            mvc.perform(
                            request.with(csrfToken())
                                    .param("userId", "2")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType("application/json"))
                    .andExpect(status().isNotFound());
        verify(uploads).get("other", 1L);
        verify(submission).submit(99L, 1L);
        verify(assistant).dispatch(eq(99L), eq(1L), any());
        verifyNoInteractions(aiLimits, index, preparation);
    }

    String signed(String issuer, String audience, Instant expiry, MacAlgorithm algorithm) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject("1")
                        .issuedAt(Instant.now().minusSeconds(1000))
                        .expiresAt(expiry)
                        .claim("sid", "session")
                        .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims))
                .getTokenValue();
    }

    @Test
    void algorithmCannotBeChangedEvenWithAnOtherwiseValidHmac() throws Exception {
        String payload = access().split("\\.")[1];
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"HS512\"}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String unsigned = header + "." + payload;
        var mac = javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        crypto.signingKey().getEncoded(), "HmacSHA512"));
        String token =
                unsigned
                        + "."
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        mac.doFinal(
                                                unsigned.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(get("/media").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(media);
    }

    @Test
    void realDecoderRejectsTamperingWrongClaimsAndExpiredTokens() throws Exception {
        var valid = access();
        String[] pieces = valid.split("\\.");
        String tampered =
                pieces[0]
                        + "."
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString("{\"sub\":\"999\"}".getBytes())
                        + "."
                        + pieces[2];
        for (String invalid :
                List.of(
                        tampered,
                        signed(
                                "wrong",
                                "vediomind-api",
                                Instant.now().plusSeconds(600),
                                MacAlgorithm.HS256),
                        signed(
                                "vediomind",
                                "wrong",
                                Instant.now().plusSeconds(600),
                                MacAlgorithm.HS256)))
            mvc.perform(get("/media").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/media")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + signed(
                                                        "vediomind",
                                                        "vediomind-api",
                                                        Instant.now().minusSeconds(60),
                                                        MacAlgorithm.HS256)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));
        assertThatThrownBy(() -> decoder.decode(valid.substring(0, valid.length() - 10)))
                .isInstanceOf(JwtException.class);
    }
}
