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

@EnabledIfEnvironmentVariable(named="STUDY_UPGRADE_MYSQL_URL",matches=".+")
class StudyUpgradeMySqlTest {
    @TempDir Path temp;
    @Test void explicitBackedUpConversionPreservesHistoryAndIsRepeatable() throws Exception {
        String url=System.getenv("STUDY_UPGRADE_MYSQL_URL");
        if (!url.matches("jdbc:mysql://[^/]+/vediomind_study_test_[A-Za-z0-9_]+(\\?.*)?")) throw new IllegalArgumentException("Isolated database required");
        String user=System.getenv("AUTH_TEST_MYSQL_USER"), password=System.getenv("AUTH_TEST_MYSQL_PASSWORD");
        var ds=new DriverManagerDataSource(url,user,password); var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("18").load().migrate();
        jdbc.update("INSERT INTO users(id,username,password) VALUES (1,'synthetic-upgrade','test-hash')");
        jdbc.update("INSERT INTO media_files(id,user_id,filename) VALUES (10,1,'synthetic lesson')");
        jdbc.update("INSERT INTO video_conversations(id,media_id,title,created_at,updated_at) VALUES (1,10,'old chat',NOW(),NOW())");
        jdbc.update("INSERT INTO video_agent_runs(id,media_id,goal,context_version,status,result_json) VALUES "
                + "('linked',10,'synthetic goal','v','SUCCEEDED',JSON_OBJECT('title','saved notes')),"
                + "('unlinked',10,'synthetic goal','v','FAILED',NULL)");
        jdbc.update("INSERT INTO video_messages(conversation_id,message_type,content,agent_run_id,created_at) VALUES (1,'AGENT_RUN','old card','linked',NOW())");
        jdbc.update("INSERT INTO video_agent_steps(run_id,stage,status,output_json) VALUES ('linked','NOTES','SUCCEEDED',JSON_OBJECT('saved',true))");
        jdbc.update("INSERT INTO video_messages(id,conversation_id,message_type,content,created_at) VALUES (100,1,'ASSISTANT','historical answer',NOW())");
        jdbc.update("INSERT INTO video_message_citations(message_id,citation_index,segment_id,start_ms,end_ms,text) VALUES (100,0,99,0,100,'historical original')");
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        var dumpProcess=new ProcessBuilder("docker","exec",System.getenv().getOrDefault("P1_MYSQL_CONTAINER","mysql-media"),"sh","-c",
                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysqldump -uroot --single-transaction --no-tablespaces \"$1\"","sh",database).start();
        String dump=new String(dumpProcess.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(dumpProcess.waitFor()).isZero();
        Path key=temp.resolve("key"); Files.writeString(key,Base64.getEncoder().encodeToString(new byte[64]));
        var crypto=new AuthCrypto(key); Path backup=temp.resolve("database.enc");
        Files.writeString(backup,crypto.encrypt(dump,"database-backup-v1"));
        assertThat(crypto.decrypt(Files.readString(backup),"database-backup-v1")).isEqualTo(dump);
        assertThatThrownBy(() -> StudyDataUpgrade.upgrade(url,user,password,1,2,database,backup,crypto))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("count changed");
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history",Integer.class)).isEqualTo(18);
        StudyDataUpgrade.upgrade(url,user,password,1,1,database,backup,crypto);
        assertThat(jdbc.queryForObject("SELECT material_id FROM video_message_citations WHERE message_id=100",Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT context_version FROM video_message_citations WHERE message_id=100",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT text FROM video_message_citations WHERE message_id=100",String.class)).isEqualTo("historical original");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_runs WHERE prepared_task IS NOT NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_conversations",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_conversations WHERE title='导入的历史任务' AND learner_id=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_runs WHERE learner_id=1 AND conversation_id IS NOT NULL AND task_scope IS NOT NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_steps",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_messages WHERE message_type='USER'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM courses",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.title')) FROM video_agent_runs WHERE id='linked'",String.class)).isEqualTo("saved notes");
        StudyDataUpgrade.upgrade(url,user,password,1,1,database,backup,crypto);
        assertThat(jdbc.queryForObject("SELECT material_id FROM video_message_citations WHERE message_id=100",Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT context_version FROM video_message_citations WHERE message_id=100",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT text FROM video_message_citations WHERE message_id=100",String.class)).isEqualTo("historical original");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_runs WHERE prepared_task IS NOT NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_conversations",Integer.class)).isEqualTo(2);
        var flyway=Flyway.configure().dataSource(ds).load(); flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
