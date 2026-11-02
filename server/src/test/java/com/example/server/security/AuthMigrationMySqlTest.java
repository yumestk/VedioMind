package com.example.server.security;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AUTH_TEST_MIGRATION_URL", matches = ".+")
class AuthMigrationMySqlTest {
    @Test
    void allVersionedMigrationsApplyAndValidateOnMySql8() {
        String url = System.getenv("AUTH_TEST_MIGRATION_URL");
        if (!url.matches("jdbc:mysql://[^/]+/vediomind_auth_test_[A-Za-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException("Isolated database required");
        var flyway =
                Flyway.configure()
                        .dataSource(
                                url,
                                System.getenv("AUTH_TEST_MYSQL_USER"),
                                System.getenv("AUTH_TEST_MYSQL_PASSWORD"))
                        .load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("18");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
