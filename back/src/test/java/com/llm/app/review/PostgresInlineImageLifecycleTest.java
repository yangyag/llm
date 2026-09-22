package com.llm.app.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.LlmApplication;
import com.llm.app.auth.api.UserRole;
import com.llm.app.auth.internal.Admin;
import com.llm.app.auth.internal.AdminRepository;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.CreateBoardPostRequest;
import com.llm.app.board.dto.UpdateBoardPostRequest;
import com.llm.app.board.exception.AttachmentStorageException;
import com.llm.app.board.model.BoardAttachment;
import com.llm.app.board.model.BoardAttachmentKind;
import com.llm.app.board.model.PostBodyFormat;
import com.llm.app.board.repository.AttachmentFileDeletionRepository;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.service.AttachmentDeletionWorker;
import com.llm.app.board.service.AttachmentStorageService;
import com.llm.app.board.service.BoardService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rich body and inline image transaction/file boundaries against a disposable localhost PostgreSQL only.
 */
@EnabledIfEnvironmentVariable(named = "LLM_TEST_POSTGRES_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")
@SpringBootTest(
    classes = LlmApplication.class,
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
class PostgresInlineImageLifecycleTest {
    private static final String SCHEMA = "inline_tx_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path DATA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "llm-postgres-inline-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Autowired
    private AdminRepository users;

    @Autowired
    private BoardPostRepository posts;

    @Autowired
    private BoardReplyRepository replies;

    @Autowired
    private BoardAttachmentRepository attachments;

    @Autowired
    private AttachmentFileDeletionRepository deletions;

    @Autowired
    private BoardService boardService;

    @Autowired
    private AttachmentDeletionWorker deletionWorker;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private AttachmentStorageService storage;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresInlineImageLifecycleTest::postgresUrl);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("app.attachments.root-path", () -> DATA_ROOT.resolve("attachments").toString());
        registry.add("app.upload-sessions.root-path", () -> DATA_ROOT.resolve("sessions").toString());
    }

    @BeforeEach
    void setUp() {
        Mockito.reset(storage);
        deleteRows();
        deleteRecursively(DATA_ROOT);
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(storage);
        deleteRows();
        deleteRecursively(DATA_ROOT);
    }

    @AfterAll
    static void tearDownSchema() {
        try (var connection = DriverManager.getConnection(postgresUrl(), "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + quoteIdentifier(SCHEMA) + " cascade");
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("failed to remove PostgreSQL test schema", exception);
        } finally {
            deleteRecursively(DATA_ROOT);
        }
    }

    @Test
    void legacyPlainFixtureIsReadableAndConvertsToRichOnPostgres() throws Exception {
        Admin owner = saveUser("legacy_inline_" + UUID.randomUUID());
        long postId = insertLegacyPlainPost(owner, "legacy plain fixture", "legacy plain body");

        BoardPostDetailResponse legacy = boardService.getPost(postId);
        assertThat(legacy.bodyFormat()).isEqualTo(PostBodyFormat.PLAIN_TEXT);
        assertThat(legacy.bodyDocument()).isNull();
        assertThat(legacy.body()).isEqualTo("legacy plain body");
        assertThat(legacy.authorUserId()).isEqualTo(owner.getId());
        assertThat(legacy.attachments()).isEmpty();

        String imageKey = UUID.randomUUID().toString();
        byte[] png = imageBytes("png", 2, 2);
        UpdateBoardPostRequest conversion = new UpdateBoardPostRequest();
        conversion.setTitle("legacy converted");
        conversion.setBodyFormat(PostBodyFormat.TIPTAP_JSON);
        conversion.setBodyDocumentBase64(encode(inlineDocument(imageKey)));
        conversion.setInlineImageManifestBase64(encode(inlineManifest(imageKey)));
        conversion.setInlineImages(List.of(new MockMultipartFile("inlineImages", "legacy.png", "image/png", png)));
        BoardPostDetailResponse converted = boardService.updatePost(owner.getId(), postId, conversion);

        assertThat(converted.bodyFormat()).isEqualTo(PostBodyFormat.TIPTAP_JSON);
        assertThat(converted.bodyDocument()).isNotNull();
        BoardAttachment inline = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).getFirst();
        assertThat(inline.getAttachmentKind()).isEqualTo(BoardAttachmentKind.INLINE_IMAGE);
        assertThat(inline.getInlineKey()).hasToString(imageKey);
        assertThat(Files.exists(attachmentFile(inline.getStoragePath()))).isTrue();
        assertThat(boardService.getInlineAttachmentContent(postId, inline.getId())
            .resource().getContentAsByteArray()).containsExactly(png);
    }

    @Test
    void richPostCreateUpdateDeleteKeepsPostgresMetadataFilesAndDeletionQueueConsistent() throws Exception {
        Admin owner = saveUser("rich_inline_" + UUID.randomUUID());
        String keyA = UUID.randomUUID().toString();
        String keyB = UUID.randomUUID().toString();
        byte[] pngA = imageBytes("png", 2, 2);
        byte[] pngB = imageBytes("png", 3, 3);

        long postId = createRichPost(owner.getId(), "rich create", keyA, pngA);
        BoardAttachment inlineA = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).getFirst();
        Path fileA = attachmentFile(inlineA.getStoragePath());
        assertThat(inlineA.getAttachmentKind()).isEqualTo(BoardAttachmentKind.INLINE_IMAGE);
        assertThat(inlineA.getInlineKey()).hasToString(keyA);
        assertThat(Files.exists(fileA)).isTrue();
        assertThat(deletions.count()).isZero();

        UpdateBoardPostRequest swap = new UpdateBoardPostRequest();
        swap.setTitle("rich update");
        swap.setBodyFormat(PostBodyFormat.TIPTAP_JSON);
        swap.setBodyDocumentBase64(encode(inlineDocument(keyB)));
        swap.setInlineImageManifestBase64(encode(inlineManifest(keyB)));
        swap.setInlineImages(List.of(new MockMultipartFile("inlineImages", "b.png", "image/png", pngB)));
        BoardPostDetailResponse updated = boardService.updatePost(owner.getId(), postId, swap);

        assertThat(updated.bodyFormat()).isEqualTo(PostBodyFormat.TIPTAP_JSON);
        assertThat(updated.attachments()).hasSize(1);
        assertThat(updated.attachments().getFirst().inlineKey()).hasToString(keyB);
        assertThat(Files.exists(fileA)).isFalse();
        BoardAttachment inlineB = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).getFirst();
        Path fileB = attachmentFile(inlineB.getStoragePath());
        assertThat(Files.exists(fileB)).isTrue();
        assertThat(deletions.count()).isZero();

        boardService.deletePost(owner.getId(), postId);

        assertThat(posts.existsById(postId)).isFalse();
        assertThat(attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId)).isEmpty();
        assertThat(Files.exists(fileB)).isFalse();
        assertThat(deletions.count()).isZero();
    }

    @Test
    void rollbackAfterInlineUpdateRemovesNewFileAndPreservesExistingOnPostgres() throws Exception {
        Admin owner = saveUser("inline_rollback_" + UUID.randomUUID());
        String keyA = UUID.randomUUID().toString();
        String keyB = UUID.randomUUID().toString();
        long postId = createRichPost(owner.getId(), "inline rollback", keyA, imageBytes("png", 2, 2));
        BoardAttachment inlineA = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).getFirst();
        Path fileA = attachmentFile(inlineA.getStoragePath());
        assertThat(Files.exists(fileA)).isTrue();

        UpdateBoardPostRequest replacement = new UpdateBoardPostRequest();
        replacement.setTitle("inline rollback replaced");
        replacement.setBodyFormat(PostBodyFormat.TIPTAP_JSON);
        replacement.setBodyDocumentBase64(encode(inlineDocument(keyB)));
        replacement.setInlineImageManifestBase64(encode(inlineManifest(keyB)));
        replacement.setInlineImages(List.of(new MockMultipartFile("inlineImages", "b.png", "image/png", imageBytes("png", 3, 3))));
        var newPath = new AtomicReference<String>();
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            boardService.updatePost(owner.getId(), postId, replacement);
            newPath.set(attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).stream()
                .filter(row -> keyB.equals(String.valueOf(row.getInlineKey())))
                .findFirst().orElseThrow().getStoragePath());
            throw new IllegalStateException("injected failure after inline update");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(posts.findById(postId).orElseThrow().getBodyDocument()).contains(keyA).doesNotContain(keyB);
        List<BoardAttachment> current = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId);
        assertThat(current).hasSize(1);
        assertThat(current.getFirst().getId()).isEqualTo(inlineA.getId());
        assertThat(current.getFirst().getInlineKey()).hasToString(keyA);
        assertThat(Files.exists(fileA)).isTrue();
        assertThat(deletions.existsById(inlineA.getStoragePath())).isFalse();
        assertThat(newPath.get()).isNotNull();
        assertThat(Files.exists(attachmentFile(newPath.get()))).isFalse();
    }

    @Test
    void committedInlineRemovalIsRetriedAfterFilesystemFailureOnPostgres() throws Exception {
        Admin owner = saveUser("inline_retry_" + UUID.randomUUID());
        String keyA = UUID.randomUUID().toString();
        long postId = createRichPost(owner.getId(), "inline removal retry", keyA, imageBytes("png", 2, 2));
        BoardAttachment inlineA = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).getFirst();
        String pathA = inlineA.getStoragePath();
        Path fileA = attachmentFile(pathA);
        Mockito.doThrow(new AttachmentStorageException("simulated I/O failure", new IOException("test fixture")))
            .when(storage).deleteIfExists(pathA);

        UpdateBoardPostRequest removal = new UpdateBoardPostRequest();
        removal.setTitle("inline removed");
        removal.setBodyFormat(PostBodyFormat.TIPTAP_JSON);
        removal.setBodyDocumentBase64(encode("{\"type\":\"doc\"}"));
        BoardPostDetailResponse result = boardService.updatePost(owner.getId(), postId, removal);

        assertThat(result.attachments()).isEmpty();
        assertThat(attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId)).isEmpty();
        assertThat(deletions.existsById(pathA)).isTrue();
        assertThat(Files.exists(fileA)).isTrue();

        Mockito.reset(storage);
        deletionWorker.retryPending();

        assertThat(deletions.existsById(pathA)).isFalse();
        assertThat(Files.exists(fileA)).isFalse();
    }

    private long createRichPost(Long userId, String title, String imageKey, byte[] png) {
        CreateBoardPostRequest request = new CreateBoardPostRequest();
        request.setTitle(title);
        request.setBodyFormat(PostBodyFormat.TIPTAP_JSON);
        request.setBodyDocumentBase64(encode(inlineDocument(imageKey)));
        request.setInlineImageManifestBase64(encode(inlineManifest(imageKey)));
        request.setInlineImages(List.of(new MockMultipartFile("inlineImages", "image.png", "image/png", png)));
        return boardService.createPost(userId, request).id();
    }

    private long insertLegacyPlainPost(Admin owner, String title, String body) throws Exception {
        try (var connection = DriverManager.getConnection(postgresUrl(), "postgres", "")) {
            connection.setSchema(SCHEMA);
            try (var statement = connection.prepareStatement(
                "insert into posts(title, body, mode, author_username, author_user_id, created_at, updated_at) "
                    + "values (?, ?, 'NORMAL', ?, ?, now(), now()) returning id")) {
                statement.setString(1, title);
                statement.setString(2, body);
                statement.setString(3, owner.getUsername());
                statement.setLong(4, owner.getId());
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        }
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
        deletions.deleteAll();
        replies.deleteAll();
        attachments.deleteAll();
        posts.deleteAll();
        users.deleteAll();
    }

    private static Path attachmentFile(String storagePath) {
        return DATA_ROOT.resolve("attachments").resolve(storagePath);
    }

    private static String inlineDocument(String imageKey) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\""
            + imageKey + "\"}}]}";
    }

    private static String inlineManifest(String imageKey) {
        return "[{\"imageKey\":\"" + imageKey + "\",\"fileIndex\":0}]";
    }

    private static byte[] imageBytes(String format, int width, int height) {
        try {
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(image, format, output)) {
                throw new IllegalStateException("missing image writer");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to build image fixture", exception);
        }
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
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
            throw new AssertionError("failed to clean PostgreSQL inline image test files", exception);
        }
    }
}
