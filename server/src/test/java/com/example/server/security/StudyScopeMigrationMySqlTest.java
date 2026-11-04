package com.example.server.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

/** Regression for the actual V20 -> V21 path, including pending-migration validation. */
@EnabledIfEnvironmentVariable(named="STUDY_P2_UPGRADE_MYSQL_URL",matches=".+")
class StudyScopeMigrationMySqlTest {
    @TempDir Path temp;
    @Test void upgradesV20HistoricalCitationsWithoutInventingTaskOrSourceVersions() throws Exception {
        String url=System.getenv("STUDY_P2_UPGRADE_MYSQL_URL");
        if(!url.matches("jdbc:mysql://[^/]+/vediomind_study_test_[A-Za-z0-9_]+(\\?.*)?")) throw new IllegalArgumentException("Isolated database required");
        String user=System.getenv("AUTH_TEST_MYSQL_USER"), password=System.getenv("AUTH_TEST_MYSQL_PASSWORD");
        var ds=new DriverManagerDataSource(url,user,password); var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("20").load().migrate();
        jdbc.update("INSERT INTO users(id,username,password) VALUES (1,'p2-upgrade','hash')");
        jdbc.update("INSERT INTO media_files(id,user_id,filename) VALUES (10,1,'historical video')");
        jdbc.update("INSERT INTO video_conversations(id,learner_id,media_id,title,created_at,updated_at) VALUES (7,1,10,'history',NOW(),NOW())");
        jdbc.update("INSERT INTO video_messages(id,conversation_id,message_type,content,created_at) VALUES (1,7,'ASSISTANT','saved answer',NOW())");
        jdbc.update("INSERT INTO video_message_citations(message_id,citation_index,segment_id,start_ms,end_ms,text) VALUES (1,0,42,100,200,'snapshot')");
        Path key=temp.resolve("key");Files.writeString(key,Base64.getEncoder().encodeToString(new byte[64])); var crypto=new AuthCrypto(key);
        Path backup=temp.resolve("synthetic.enc");Files.writeString(backup,crypto.encrypt("CREATE TABLE `video_agent_runs`; CREATE TABLE `users`;", "database-backup-v1"));
        StudyDataUpgrade.upgrade(url,user,password,1,0,jdbc.queryForObject("SELECT DATABASE()",String.class),backup,crypto);
        assertThat(jdbc.queryForObject("SELECT material_id FROM video_message_citations",Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT context_version FROM video_message_citations",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT evidence_ref FROM video_message_citations",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT text FROM video_message_citations",String.class)).isEqualTo("snapshot");
        assertThat(jdbc.queryForObject("SELECT prepared_task FROM video_messages",String.class)).isNull();
        StudyDataUpgrade.upgrade(url,user,password,1,0,jdbc.queryForObject("SELECT DATABASE()",String.class),backup,crypto);
        var current=Flyway.configure().dataSource(ds).load(); current.validate();
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_message_citations",Integer.class)).isEqualTo(1);
    }
}
