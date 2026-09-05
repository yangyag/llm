package com.llm.app.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.llm.app.LlmApplication;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Run against a disposable localhost PostgreSQL, never an application database. */
@EnabledIfEnvironmentVariable(named = "LLM_TEST_POSTGRES_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")
class PostgresMigrationTest {
    @TempDir Path scratch;

    @Test
    void upgradePreservesProvenOwnersRejectsReusedNamesAndPassesHibernateValidation() throws Exception {
        String url = System.getenv("LLM_TEST_POSTGRES_URL");
        String schema = "validation_" + UUID.randomUUID().toString().replace("-", "");
        var config = Flyway.configure().dataSource(url, "postgres", "").schemas(schema).defaultSchema(schema);
        config.target("16").load().migrate();
        try (var connection = DriverManager.getConnection(url, "postgres", ""); var sql = connection.createStatement()) {
            connection.setSchema(schema);
            sql.execute("""
                insert into admins(username, password_hash, role, created_at) values
                    ('provenowner', 'unused-test-hash', 'USER', '2000-01-01Z'),
                    ('reusedowner', 'unused-test-hash', 'USER', '2025-01-01Z');
                insert into posts(title, body, mode, author_username, created_at, updated_at) values
                    ('proven', '', 'NORMAL', 'provenowner', '2020-01-01Z', '2020-01-01Z'),
                    ('reused', '', 'NORMAL', 'reusedowner', '2020-01-01Z', '2020-01-01Z'),
                    ('unknown', '', 'NORMAL', null, '2020-01-01Z', '2020-01-01Z');
                insert into post_replies(post_id, body, is_ai, author_username, created_at, updated_at)
                    select id, 'human', false, 'provenowner', '2020-01-01Z', '2020-01-01Z' from posts where title = 'proven';
                insert into post_replies(post_id, body, is_ai, author_username, created_at, updated_at)
                    select id, 'legacy AI', true, 'provenowner', '2020-01-01Z', '2020-01-01Z' from posts where title = 'proven';
                insert into upload_sessions(id, archive_name, status, created_by, created_at, updated_at, expires_at,
                    file_size_bytes, chunk_size_bytes, total_chunks, file_sha256) values
                    ('11111111-1111-1111-1111-111111111111', 'proven.zip', 'PENDING', 'provenowner',
                     '2020-01-01Z', '2020-01-01Z', '2099-01-01Z', 3, 4, 1, repeat('0', 64)),
                    ('22222222-2222-2222-2222-222222222222', 'reused.zip', 'PENDING', 'reusedowner',
                     '2020-01-01Z', '2020-01-01Z', '2099-01-01Z', 3, 4, 1, repeat('0', 64));
                """);
            Flyway flyway = Flyway.configure().dataSource(url, "postgres", "").schemas(schema).defaultSchema(schema).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
            try (var rows = sql.executeQuery("select title, author_user_id from posts order by title")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("proven"); assertThat(rows.getObject(2)).isNotNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("reused"); assertThat(rows.getObject(2)).isNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("unknown"); assertThat(rows.getObject(2)).isNull();
            }
            try (var rows = sql.executeQuery("select is_ai, author_user_id from post_replies order by is_ai")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isFalse(); assertThat(rows.getObject(2)).isNotNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue(); assertThat(rows.getObject(2)).isNull();
            }
            try (var rows = sql.executeQuery("select created_by_user_id from upload_sessions order by archive_name")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNotNull();
                assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull();
            }
            sql.execute("delete from admins where username = 'provenowner'");
            sql.execute("insert into admins(username, password_hash, role, created_at) values ('provenowner', 'unused-test-hash', 'USER', now())");
            try (var rows = sql.executeQuery("select count(*) from posts where author_user_id is not null")) {
                rows.next(); assertThat(rows.getLong(1)).isZero();
            }
            try (var rows = sql.executeQuery("select count(*) from post_replies where author_user_id is not null")) {
                rows.next(); assertThat(rows.getLong(1)).isZero();
            }
            try (var rows = sql.executeQuery("select count(*) from upload_sessions where created_by_user_id is not null")) {
                rows.next(); assertThat(rows.getLong(1)).isZero();
            }
        }

        // Validate actual PostgreSQL types against every JPA entity after the upgrade.
        try (var context = new SpringApplicationBuilder(LlmApplication.class).run(
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--spring.flyway.enabled=true", "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                "--app.attachments.root-path=" + scratch.resolve("attachments"),
                "--app.upload-sessions.root-path=" + scratch.resolve("sessions"))) {
            assertThat(context.isActive()).isTrue();
        }
    }
}
