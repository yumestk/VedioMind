package com.example.server.security;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/** Explicit offline P1 conversion. No Spring, broker, AI calls, or startup data inference. */
public final class StudyDataUpgrade {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Use <confirmed-owner-id> <expected-unlinked-runs> <database> <encrypted-backup>");
        long owner = Long.parseLong(args[0]);
        int expected = Integer.parseInt(args[1]);
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(System.getenv().getOrDefault("AUTH_DB_CONFIG", "src/main/resources/application.properties")))) {
            config.load(reader);
        }
        String url = value(config, "AUTH_DB_URL", "spring.datasource.url");
        String user = value(config, "AUTH_DB_USER", "spring.datasource.username");
        String password = value(config, "AUTH_DB_PASSWORD", "spring.datasource.password");
        var crypto = new AuthCrypto(Path.of(System.getenv().getOrDefault("AUTH_KEY_FILE", System.getProperty("user.home") + "/.vediomind/auth.key")));
        upgrade(url, user, password, owner, expected, args[2], Path.of(args[3]), crypto);
    }

    static void upgrade(String url, String user, String password, long owner, int expected,
                        String database, Path backupFile, AuthCrypto crypto) throws Exception {
        String backup = crypto.decrypt(Files.readString(backupFile), "database-backup-v1");
        if (!backup.contains("CREATE TABLE `video_agent_runs`") || !backup.contains("CREATE TABLE `users`"))
            throw new IllegalArgumentException("A complete encrypted database backup is required");
        try (var db = DriverManager.getConnection(url, user, password)) {
            if (!database.equals(db.getCatalog())) throw new IllegalArgumentException("Backup database does not match configured database");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(db, true));
            if (jdbc.queryForObject("SELECT GET_LOCK('vediomind-study-p1-upgrade',0)", Integer.class) != 1)
                throw new IllegalStateException("Another offline conversion is running");
            try {
                for (String table : List.of("analysis_jobs", "video_agent_runs")) {
                    if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE status NOT IN ('SUCCEEDED','PARTIAL','FAILED')", Integer.class) != 0)
                        throw new IllegalStateException("Stop and drain background tasks before conversion");
                }
                String version = jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
                if (Integer.parseInt(version) >= 20) { var current = Flyway.configure().dataSource(url,user,password).load(); current.migrate(); current.validate(); System.out.println("Historical ownership already converted; schema migrations applied and validated."); return; }
                if (!List.of("18", "19").contains(version)) throw new IllegalStateException("Expected schema V18 or V19");
                if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, owner) != 1
                        || jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE user_id IS NULL OR user_id<>?", Integer.class, owner) != 0)
                    throw new IllegalStateException("Confirmed single-owner mapping no longer holds; audit again");
                int unlinked = jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_runs r WHERE NOT EXISTS "
                        + "(SELECT 1 FROM video_messages m WHERE m.agent_run_id=r.id)", Integer.class);
                if (unlinked != expected) throw new IllegalStateException("Unlinked run count changed; audit again");
                int badLinks = jdbc.queryForObject("SELECT COUNT(*) FROM video_messages m JOIN video_agent_runs r ON r.id=m.agent_run_id "
                        + "JOIN video_conversations c ON c.id=m.conversation_id WHERE c.media_id<>r.media_id", Integer.class);
                if (badLinks != 0) throw new IllegalStateException("Cross-media history requires manual resolution");
                Flyway.configure().dataSource(url,user,password).target("19").load().migrate();
                db.setAutoCommit(false);
                try {
                    jdbc.update("UPDATE video_conversations SET learner_id=? WHERE learner_id IS NULL", owner);
                    jdbc.update("UPDATE video_agent_runs r JOIN video_messages m ON m.agent_run_id=r.id "
                            + "JOIN video_conversations c ON c.id=m.conversation_id SET r.conversation_id=c.id,r.learner_id=c.learner_id "
                            + "WHERE r.conversation_id IS NULL");
                    var runs = jdbc.queryForList("SELECT id,media_id,created_at,goal FROM video_agent_runs WHERE conversation_id IS NULL ORDER BY id");
                    for (var run : runs) {
                        long conversationId;
                        try (var insert = db.prepareStatement("INSERT INTO video_conversations(media_id,learner_id,title,created_at,updated_at) "
                                + "VALUES (?,?,'导入的历史任务',?,?)", Statement.RETURN_GENERATED_KEYS)) {
                            insert.setObject(1,run.get("media_id")); insert.setLong(2,owner);
                            insert.setObject(3,run.get("created_at")); insert.setObject(4,run.get("created_at")); insert.executeUpdate();
                            try (var keys = insert.getGeneratedKeys()) { keys.next(); conversationId=keys.getLong(1); }
                        }
                        jdbc.update("UPDATE video_agent_runs SET learner_id=?,conversation_id=? WHERE id=?", owner,conversationId,run.get("id"));
                        // Imported result only: do not fabricate historical user/chat messages.
                        jdbc.update("INSERT INTO video_messages(conversation_id,message_type,content,agent_run_id,created_at) VALUES (?,'AGENT_RUN',?,?,?)",
                                conversationId,run.get("goal"),run.get("id"),run.get("created_at"));
                    }
                    jdbc.update("UPDATE video_agent_runs SET task_scope=JSON_OBJECT('type','VIDEO','mediaIds',JSON_ARRAY(media_id)) WHERE task_scope IS NULL");
                    db.commit();
                    System.out.printf("Historical conversations assigned to confirmed learner; imported standalone runs=%d. No course created or published.%n", runs.size());
                } catch (Exception error) { db.rollback(); throw error; }
                finally { db.setAutoCommit(true); }
                var flyway = Flyway.configure().dataSource(url,user,password).load();
                flyway.migrate(); flyway.validate();
                System.out.println("Study schema upgraded and validated.");
            } finally { jdbc.queryForObject("SELECT RELEASE_LOCK('vediomind-study-p1-upgrade')", Integer.class); }
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("Offline database operation failed, SQLState=" + error.getSQLState());
        }
    }
    private static String value(Properties config, String env, String key) {
        String result = System.getenv().getOrDefault(env, config.getProperty(key, ""));
        if (result.isBlank() || result.contains("${")) throw new IllegalArgumentException("Set " + env);
        return result;
    }
}
