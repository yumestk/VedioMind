package com.example.server.security;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "STUDY_P3_UPGRADE_MYSQL_URL", matches = ".+")
class StudyExecutionMigrationMySqlTest {
    @Test
    void v21UpgradePreservesCompletedResultsAndStopsUnsafeLegacyActiveWork() {
        String url = System.getenv("STUDY_P3_UPGRADE_MYSQL_URL");
        if (!url.matches("jdbc:mysql://[^/]+/vediomind_study_test_[A-Za-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException("Isolated database required");
        var ds =
                new DriverManagerDataSource(
                        url,
                        System.getenv("AUTH_TEST_MYSQL_USER"),
                        System.getenv("AUTH_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("21").load().migrate();
        jdbc.update("INSERT INTO users(id,username,password) VALUES (1,'migration','hash')");
        jdbc.update("INSERT INTO media_files(id,user_id,filename) VALUES (10,1,'history')");
        jdbc.update(
                "INSERT INTO"
                    + " video_conversations(id,learner_id,media_id,title,created_at,updated_at)"
                    + " VALUES (7,1,10,'saved',NOW(),NOW())");
        jdbc.update(
                "INSERT INTO"
                    + " video_agent_runs(id,media_id,learner_id,conversation_id,task_scope,goal,context_version,status,result_json,input_tokens,output_tokens,generation_calls)"
                    + " VALUES"
                    + " ('done',10,1,7,JSON_OBJECT('type','VIDEO'),'notes','v','SUCCEEDED',JSON_OBJECT('title','saved'),123,10,2),('active',10,1,7,JSON_OBJECT('type','VIDEO'),'notes','v','DRAFTING',NULL,0,0,1)");
        var flyway = Flyway.configure().dataSource(ds).load();
        flyway.migrate();
        flyway.validate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM video_agent_runs WHERE id='done'",
                                String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.title')) FROM"
                                    + " video_agent_runs WHERE id='done'",
                                String.class))
                .isEqualTo("saved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM video_agent_runs WHERE id='done'",
                                Integer.class))
                .isEqualTo(123);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT status,stop_code FROM video_agent_runs WHERE id='active'"))
                .containsEntry("status", "FAILED")
                .containsEntry("stop_code", "TASK_RUNTIME_CHANGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_attempts", Integer.class))
                .isZero();
        for (String terminal : java.util.List.of("CANCELLED", "NEEDS_INPUT"))
            jdbc.update(
                    "INSERT INTO"
                        + " video_agent_runs(id,media_id,learner_id,conversation_id,task_scope,goal,context_version,status)"
                        + " VALUES (?,10,1,7,JSON_OBJECT('type','VIDEO'),'notes','v',?)",
                    terminal,
                    terminal);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
