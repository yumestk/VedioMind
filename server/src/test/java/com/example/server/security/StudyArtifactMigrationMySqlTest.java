package com.example.server.security;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "STUDY_P4_UPGRADE_MYSQL_URL", matches = ".+")
class StudyArtifactMigrationMySqlTest {
    @Test
    void v22UpgradeIsAdditiveAndArtifactConstraintsAreReal() {
        String url = System.getenv("STUDY_P4_UPGRADE_MYSQL_URL");
        if (!url.matches("jdbc:mysql://[^/]+/vediomind_study_test_[A-Za-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException("Isolated database required");
        var ds =
                new DriverManagerDataSource(
                        url,
                        System.getenv("AUTH_TEST_MYSQL_USER"),
                        System.getenv("AUTH_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(ds).target("22").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO users(id,username,password) VALUES (1,'migration','hash')");
        jdbc.update("INSERT INTO media_files(id,user_id,filename) VALUES (10,1,'history')");
        jdbc.update(
                "INSERT INTO"
                        + " video_conversations(id,learner_id,media_id,title,created_at,updated_at)"
                        + " VALUES (7,1,10,'saved',NOW(),NOW())");
        jdbc.update(
                "INSERT INTO"
                    + " video_agent_runs(id,media_id,learner_id,conversation_id,task_scope,goal,context_version,status,result_json)"
                    + " VALUES"
                    + " ('done',10,1,7,JSON_OBJECT('type','VIDEO'),'notes','v','SUCCEEDED',JSON_OBJECT('title','saved'))");
        var current = Flyway.configure().dataSource(ds).load();
        current.migrate();
        current.validate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.title')) FROM"
                                        + " video_agent_runs WHERE id='done'",
                                String.class))
                .isEqualTo("saved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_artifacts", Integer.class))
                .isZero();
        String insert =
                "INSERT INTO"
                    + " study_artifacts(id,run_id,learner_id,slot_id,kind,operation_id,payload_fingerprint,base_version,content_json,citations_json,coverage_json,created_at,updated_at)"
                    + " VALUES (?,'done',1,? ,? ,? ,REPEAT('a',64),0,'{}','[]','[]',NOW(),NOW())";
        jdbc.update(insert, "a", "slot", "NOTE", "op");
        assertThatThrownBy(() -> jdbc.update(insert, "b", "slot", "NOTE", "op2"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "b", "slot2", "QUIZ", "op2"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_artifact_kind");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM video_agent_runs WHERE id='done'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(current.migrate().migrationsExecuted).isZero();
    }
}
