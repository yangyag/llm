package com.llm.app.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import com.llm.app.LlmApplication;
import com.llm.app.auth.api.UserRole;
import com.llm.app.auth.internal.Admin;
import com.llm.app.auth.internal.AdminRepository;
import com.llm.app.board.api.upload.UploadedPostCreationCommand;
import com.llm.app.board.api.upload.UploadedPostCreationResult;
import com.llm.app.board.api.upload.UploadedPostCreator;
import com.llm.app.board.repository.AttachmentFileDeletionRepository;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.service.BoardUploadedPostCreator;
import com.llm.app.upload.dto.CreateUploadSessionRequest;
import com.llm.app.upload.model.UploadSession;
import com.llm.app.upload.model.UploadSessionPart;
import com.llm.app.upload.model.UploadSessionStatus;
import com.llm.app.upload.repository.UploadSessionPartRepository;
import com.llm.app.upload.repository.UploadSessionRepository;
import com.llm.app.upload.service.UploadSessionService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Finalize transaction boundaries against a disposable localhost PostgreSQL only.
 */
@EnabledIfEnvironmentVariable(named = "LLM_TEST_POSTGRES_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")
@SpringBootTest(
    classes = { LlmApplication.class, PostgresUploadFinalizeTest.TestBeans.class },
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.username=postgres",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-timeout=5000",
        "spring.datasource.hikari.validation-timeout=2000",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '1s'; SET statement_timeout = '10s'"
    }
)
class PostgresUploadFinalizeTest {
    private static final String SCHEMA = "upload_tx_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path DATA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "llm-postgres-upload-" + UUID.randomUUID()
    ).toAbsolutePath();
    private static final byte[] ZIP_BYTES = "PK-postgres-upload-fixture".getBytes(StandardCharsets.UTF_8);
    private static final Duration DATABASE_OPERATION_TIMEOUT = Duration.ofSeconds(5);
    private static final String INJECTED_FAILURE = "injected board creator failure";
    private static final String DELETE_FLUSH_FAILURE = "injected session delete flush failure";
    private static final String DEFERRED_FAILURE = "deferred upload session delete rejection";
    private static final String TRIGGER_NAME = "reject_upload_session_delete";
    private static final String FUNCTION_NAME = "reject_upload_session_delete";

    @Autowired
    private AdminRepository users;

    @Autowired
    private BoardPostRepository posts;

    @Autowired
    private BoardReplyRepository replies;

    @Autowired
    private BoardAttachmentRepository attachments;

    @Autowired
    private AttachmentFileDeletionRepository attachmentFileDeletions;

    @Autowired
    private UploadSessionRepository sessions;

    @Autowired
    private UploadSessionPartRepository parts;

    @Autowired
    private UploadSessionService uploadSessionService;

    @Autowired
    private DeleteFailureControl deleteFailureControl;

    @Autowired
    private TestUploadedPostCreator uploadedPostCreator;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresUploadFinalizeTest::postgresUrl);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("app.attachments.root-path", () -> DATA_ROOT.resolve("attachments").toString());
        registry.add("app.upload-sessions.root-path", () -> DATA_ROOT.resolve("sessions").toString());
    }

    @BeforeEach
    void setUp() {
        uploadedPostCreator.reset();
        deleteFailureControl.resetState();
        deleteRows();
        deleteRecursively(DATA_ROOT);
    }

    @AfterEach
    void tearDown() {
        uploadedPostCreator.reset();
        deleteFailureControl.resetState();
        deleteRows();
        deleteRecursively(DATA_ROOT);
    }

    @AfterAll
    static void tearDownSchema() {
        try (var connection = java.sql.DriverManager.getConnection(postgresUrl(), "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + quoteIdentifier(SCHEMA) + " cascade");
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("failed to remove PostgreSQL test schema", exception);
        } finally {
            deleteRecursively(DATA_ROOT);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void bodyFailureAfterSessionAndPartDeleteFlushPreservesOriginalFailureAndLeavesRetryableSession() throws Exception {
        Long userId = saveUser("upload_delete_flush_" + UUID.randomUUID()).getId();
        UUID sessionId = createUploadedSession(userId, "delete-flush-failure.zip");
        UploadSessionPart originalPart = parts.findBySession_IdAndChunkNumber(sessionId, 1).orElseThrow();
        Path originalChunk = DATA_ROOT.resolve("sessions").resolve(originalPart.getStoragePath());
        assertThat(Files.readAllBytes(originalChunk)).containsExactly(ZIP_BYTES);

        deleteFailureControl.failAfterSessionDeleteFlush();
        Throwable failure = catchThrowable(() -> assertTimeout(
            DATABASE_OPERATION_TIMEOUT,
            () -> uploadSessionService.finalizeSession(userId, sessionId)
        ));

        assertThat(deleteFailureControl.observed()).withFailMessage("failure=%s", failure).isTrue();
        assertThat(failure).isInstanceOf(IllegalStateException.class)
            .hasMessage(DELETE_FLUSH_FAILURE);
        assertThat(posts.count()).isZero();
        assertThat(attachments.count()).isZero();
        assertThat(attachmentFileDeletions.count()).isZero();
        assertThat(listFiles(DATA_ROOT.resolve("attachments"))).isEmpty();
        assertThat(uploadedPostCreator.lastAssembledPath()).isNotNull();
        assertThat(uploadedPostCreator.lastAssembledPath()).doesNotExist();
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(UploadSessionStatus.FAILED);
        assertThat(parts.findBySession_IdOrderByChunkNumberAsc(sessionId)).hasSize(1);
        assertThat(Files.exists(originalChunk)).isTrue();
        assertThat(Files.readAllBytes(originalChunk)).containsExactly(ZIP_BYTES);
        assertThat(assembledFiles(sessionId)).isEmpty();

        assertThat(catchThrowable(() -> assertTimeout(
            DATABASE_OPERATION_TIMEOUT,
            () -> uploadSessionService.getSession(userId, sessionId)
        ))).isNull();
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(UploadSessionStatus.FAILED);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void bodyFailureAfterFinalizingFlushPreservesOriginalFailureAndLeavesRetryableSession() throws Exception {
        Long userId = saveUser("upload_failure_" + UUID.randomUUID()).getId();
        UUID sessionId = createUploadedSession(userId, "body-failure.zip");
        UploadSessionPart originalPart = parts.findBySession_IdAndChunkNumber(sessionId, 1).orElseThrow();
        Path originalChunk = DATA_ROOT.resolve("sessions").resolve(originalPart.getStoragePath());
        assertThat(Files.readAllBytes(originalChunk)).containsExactly(ZIP_BYTES);

        uploadedPostCreator.failAfterFlush();
        Throwable failure = catchThrowable(() -> assertTimeout(
            DATABASE_OPERATION_TIMEOUT,
            () -> uploadSessionService.finalizeSession(userId, sessionId)
        ));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
            .hasMessage(INJECTED_FAILURE);
        assertThat(posts.count()).isZero();
        assertThat(attachments.count()).isZero();
        assertThat(listFiles(DATA_ROOT.resolve("attachments"))).isEmpty();
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(UploadSessionStatus.FAILED);
        assertThat(parts.findBySession_IdOrderByChunkNumberAsc(sessionId)).hasSize(1);
        assertThat(Files.exists(originalChunk)).isTrue();
        assertThat(Files.readAllBytes(originalChunk)).containsExactly(ZIP_BYTES);
        assertThat(assembledFiles(sessionId)).isEmpty();

        UploadSessionService serviceProxy = uploadSessionService;
        assertThat(catchThrowable(() -> assertTimeout(
            DATABASE_OPERATION_TIMEOUT,
            () -> serviceProxy.getSession(userId, sessionId)
        ))).isNull();
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(UploadSessionStatus.FAILED);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void deferredDeleteFailureEscapesCommitAndRollbackSynchronizationCleansOnlyAssembly() throws Exception {
        Long userId = saveUser("upload_commit_" + UUID.randomUUID()).getId();
        UUID sessionId = createUploadedSession(userId, "commit-failure.zip");
        UploadSessionPart originalPart = parts.findBySession_IdAndChunkNumber(sessionId, 1).orElseThrow();
        Path originalChunk = DATA_ROOT.resolve("sessions").resolve(originalPart.getStoragePath());
        installDeferredDeleteFailure(sessionId);

        try {
            Throwable failure = catchThrowable(() -> assertTimeout(
                DATABASE_OPERATION_TIMEOUT,
                () -> uploadSessionService.finalizeSession(userId, sessionId)
            ));

            assertThat(failure).isNotNull();
            assertThat(causeMessages(failure)).contains(DEFERRED_FAILURE);
            assertThat(uploadedPostCreator.lastAssembledPath()).isNotNull();
            assertThat(uploadedPostCreator.lastAssembledPath()).doesNotExist();
            assertThat(posts.count()).isZero();
            assertThat(attachments.count()).isZero();
            assertThat(listFiles(DATA_ROOT.resolve("attachments"))).isEmpty();
            assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(UploadSessionStatus.PENDING);
            assertThat(parts.findBySession_IdOrderByChunkNumberAsc(sessionId)).hasSize(1);
            assertThat(Files.exists(originalChunk)).isTrue();
            assertThat(Files.readAllBytes(originalChunk)).containsExactly(ZIP_BYTES);
            assertThat(assembledFiles(sessionId)).isEmpty();
        } finally {
            dropDeferredDeleteFailure();
        }
    }

    private UUID createUploadedSession(Long userId, String archiveName) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(ZIP_BYTES);
        String sha256 = HexFormatHolder.sha256(ZIP_BYTES);
        UploadSessionService serviceProxy = uploadSessionService;
        UUID sessionId = serviceProxy.createSession(
            userId,
            new CreateUploadSessionRequest(archiveName, ZIP_BYTES.length, encoded.length(), 1, sha256)
        ).sessionId();
        serviceProxy.uploadChunk(userId, sessionId, 1, encoded);
        return sessionId;
    }

    private Admin saveUser(String username) {
        Admin user = new Admin();
        user.setUsername(username);
        user.setPasswordHash("unused-test-hash");
        user.setRole(UserRole.USER);
        user.setCreatedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private void deleteRows() {
        attachmentFileDeletions.deleteAll();
        replies.deleteAll();
        attachments.deleteAll();
        posts.deleteAll();
        parts.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
    }

    private void installDeferredDeleteFailure(UUID sessionId) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(postgresUrl(), "postgres", "");
             var statement = connection.createStatement()) {
            String schema = quoteIdentifier(SCHEMA);
            statement.execute("""
                create or replace function %s.%s() returns trigger
                language plpgsql as $function$
                begin
                    if old.id = '%s'::uuid then
                        raise exception '%s';
                    end if;
                    return old;
                end;
                $function$
                """.formatted(schema, quoteIdentifier(FUNCTION_NAME), sessionId, DEFERRED_FAILURE));
            statement.execute("""
                create constraint trigger %s
                after delete on %s.upload_sessions
                deferrable initially deferred
                for each row execute function %s.%s()
                """.formatted(quoteIdentifier(TRIGGER_NAME), schema, schema, quoteIdentifier(FUNCTION_NAME)));
        }
    }

    private void dropDeferredDeleteFailure() {
        try (var connection = java.sql.DriverManager.getConnection(postgresUrl(), "postgres", "");
             var statement = connection.createStatement()) {
            String schema = quoteIdentifier(SCHEMA);
            statement.execute("drop trigger if exists " + quoteIdentifier(TRIGGER_NAME) + " on " + schema + ".upload_sessions");
            statement.execute("drop function if exists " + schema + "." + quoteIdentifier(FUNCTION_NAME) + "()");
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("failed to remove PostgreSQL deferred test constraint", exception);
        }
    }

    private List<Path> assembledFiles(UUID sessionId) throws IOException {
        Path sessionDirectory = DATA_ROOT.resolve("sessions").resolve(sessionId.toString());
        if (!Files.exists(sessionDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(sessionDirectory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("assembled-")).toList();
        }
    }

    private List<Path> listFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static String postgresUrl() {
        String url = System.getenv("LLM_TEST_POSTGRES_URL");
        if (url == null) {
            throw new IllegalStateException("LLM_TEST_POSTGRES_URL is required for PostgreSQL integration tests");
        }
        return url;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException | UncheckedIOException exception) {
            throw new AssertionError("failed to clean PostgreSQL upload test files", exception);
        }
    }

    static class HexFormatHolder {
        private static String sha256(byte[] bytes) throws Exception {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        TestUploadedPostCreator uploadedPostCreator(BoardUploadedPostCreator delegate, EntityManager entityManager) {
            return new TestUploadedPostCreator(delegate, entityManager);
        }

        @Bean
        DeleteFailureControl deleteFailureControl(
            @Qualifier("uploadSessionRepository") UploadSessionRepository uploadSessionRepository,
            EntityManager entityManager
        ) {
            return new DeleteFailureControl(uploadSessionRepository, entityManager);
        }

        @Bean
        @Primary
        UploadSessionRepository uploadSessionRepositoryDecorator(DeleteFailureControl control) {
            return control.repository();
        }
    }

    static class DeleteFailureControl {
        private final UploadSessionRepository delegate;
        private final EntityManager entityManager;
        private volatile boolean observed;
        private final UploadSessionRepository repository;

        DeleteFailureControl(UploadSessionRepository delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.repository = mock(UploadSessionRepository.class, delegatesTo(delegate));
        }

        UploadSessionRepository repository() {
            return repository;
        }

        void failAfterSessionDeleteFlush() {
            doAnswer(invocation -> {
                delegate.delete(invocation.getArgument(0));
                entityManager.flush();
                UploadSession session = invocation.getArgument(0);
                long remainingSessions = ((Number) entityManager.createNativeQuery(
                    "select count(*) from " + quoteIdentifier(SCHEMA) + ".upload_sessions where id = :id"
                ).setParameter("id", session.getId()).getSingleResult()).longValue();
                long remainingParts = ((Number) entityManager.createNativeQuery(
                    "select count(*) from " + quoteIdentifier(SCHEMA) + ".upload_session_parts where session_id = :id"
                ).setParameter("id", session.getId()).getSingleResult()).longValue();
                observed = remainingSessions == 0 && remainingParts == 0;
                throw new IllegalStateException(DELETE_FLUSH_FAILURE);
            }).when(repository).delete(any(UploadSession.class));
        }

        boolean observed() {
            return observed;
        }

        void resetState() {
            observed = false;
            reset(repository);
        }
    }

    static class TestUploadedPostCreator implements UploadedPostCreator {
        private final BoardUploadedPostCreator delegate;
        private final EntityManager entityManager;
        private volatile boolean failAfterFlush;
        private volatile Path assembledPath;

        TestUploadedPostCreator(BoardUploadedPostCreator delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
        }

        @Override
        public UploadedPostCreationResult create(UploadedPostCreationCommand command) {
            assembledPath = command.assembledPath();
            UploadedPostCreationResult result = delegate.create(command);
            if (failAfterFlush) {
                entityManager.flush();
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            return result;
        }

        void failAfterFlush() {
            failAfterFlush = true;
        }

        Path lastAssembledPath() {
            return assembledPath;
        }

        void reset() {
            failAfterFlush = false;
            assembledPath = null;
        }
    }
}
