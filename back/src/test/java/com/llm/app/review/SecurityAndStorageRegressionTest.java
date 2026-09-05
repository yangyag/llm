package com.llm.app.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.auth.Admin;
import com.llm.app.auth.AdminRepository;
import com.llm.app.auth.JwtProvider;
import com.llm.app.auth.UserRole;
import com.llm.app.board.dto.CreateUploadSessionRequest;
import com.llm.app.board.dto.UploadSessionStatusResponse;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.repository.UploadSessionPartRepository;
import com.llm.app.board.repository.UploadSessionRepository;
import com.llm.app.board.service.BoardService;
import com.llm.app.board.service.UploadSessionWireCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Regression coverage for account identity, transaction rollback, deletion retry and upload title limits. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:review20260905;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc(printOnlyOnFailure = false, print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class SecurityAndStorageRegressionTest {
    private static final Path DATA_ROOT = Path.of("build-local", "review-data-" + UUID.randomUUID()).toAbsolutePath();
    private static final byte[] ZIP_BYTES = "PK-review-fixture".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void isolateStorage(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.root-path", () -> DATA_ROOT.resolve("attachments").toString());
        registry.add("app.upload-sessions.root-path", () -> DATA_ROOT.resolve("sessions").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired AdminRepository users;
    @Autowired BoardPostRepository posts;
    @Autowired BoardReplyRepository replies;
    @Autowired BoardAttachmentRepository attachments;
    @Autowired UploadSessionRepository sessions;
    @Autowired UploadSessionPartRepository parts;
    @Autowired UploadSessionWireCodec wire;
    @Autowired BoardService boardService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.llm.app.board.repository.AttachmentFileDeletionRepository deletions;
    @Autowired com.llm.app.board.service.AttachmentDeletionWorker deletionWorker;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.llm.app.board.service.AttachmentStorageService storage;

    private String adminToken;

    @BeforeEach
    void setUp() {
        deletions.deleteAll();
        parts.deleteAll();
        sessions.deleteAll();
        attachments.deleteAll();
        replies.deleteAll();
        posts.deleteAll();
        users.deleteAll();
        saveUser("reviewadmin", UserRole.ADMIN);
        adminToken = jwt.generateToken("reviewadmin");
    }

    @Test
    void deletedAccountIsRejectedByEveryWriteAndUploadEndpoint() throws Exception {
        Admin removed = saveUser("removedmember", UserRole.USER);
        String oldToken = jwt.generateToken(removed.getUsername());
        long postId = createPost(oldToken, "before deletion");
        UUID sessionId = createAndUpload(oldToken, "before-deletion.zip");
        mvc.perform(delete("/api/v1/users/{id}", removed.getId()).header("Authorization", bearer(adminToken)))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(oldToken)))
            .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/posts").header("Authorization", bearer(oldToken)).param("title", "denied"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/posts/{id}/replies", postId).header("Authorization", bearer(oldToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("bodyBase64", encode(ZIP_BYTES)))))
            .andExpect(status().isUnauthorized());
        var create = new CreateUploadSessionRequest("denied.zip", 3, 4, 1, "0".repeat(64));
        mvc.perform(post("/api/v1/upload-sessions").header("Authorization", bearer(oldToken))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(wire.encodeCreateRequest(create))))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/upload-sessions/{id}", sessionId).header("Authorization", bearer(oldToken)))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/upload-sessions/{id}/chunks", sessionId).header("Authorization", bearer(oldToken))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(wire.encodeChunkRequest(1, encode(ZIP_BYTES)))))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/upload-sessions/{id}/finalize", sessionId).header("Authorization", bearer(oldToken)))
            .andExpect(status().isUnauthorized());
        assertThat(posts.count()).isEqualTo(1);
        assertThat(replies.count()).isZero();
    }

    @Test
    void reusedUsernameDoesNotReviveOldToken() throws Exception {
        Admin removed = saveUser("reusedmember", UserRole.USER);
        Long oldId = removed.getId();
        String oldToken = jwt.generateToken(removed.getUsername());
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(oldToken)))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/{id}", oldId)
                .header("Authorization", bearer(adminToken)))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(oldToken)))
            .andExpect(status().isUnauthorized());

        // This credential is generated only for this in-memory test and is never printed.
        mvc.perform(post("/api/v1/users")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "username", "reusedmember", "password", UUID.randomUUID().toString(), "role", "ADMIN"))))
            .andExpect(status().isCreated());
        assertThat(users.findByUsername("reusedmember").orElseThrow().getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(oldToken)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(oldToken)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void transactionRollbackPreservesAttachmentRowAndFile() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/posts")
                .file(new MockMultipartFile("attachments", "review.txt", "text/plain", ZIP_BYTES))
                .header("Authorization", bearer(adminToken))
                .param("title", "rollback fixture"))
            .andExpect(status().isCreated()).andReturn();
        long postId = mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        var attachment = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).getFirst();
        Path file = DATA_ROOT.resolve("attachments").resolve(attachment.getStoragePath());
        assertThat(Files.exists(file)).isTrue();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            boardService.deletePost(users.findByUsername("reviewadmin").orElseThrow().getId(), postId);
            throw new IllegalStateException("review injected failure before commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(posts.existsById(postId)).isTrue();
        assertThat(attachments.existsById(attachment.getId())).isTrue();
        assertThat(Files.exists(file)).isTrue();
        mvc.perform(get("/api/v1/posts/{id}/attachments/{attachmentId}", postId, attachment.getId()))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(ZIP_BYTES));
    }

    @Test
    void longArchiveNamesFinalizeWithBoundedTitleAndOriginalFilename() throws Exception {
        for (String archiveName : new String[] { "a".repeat(188) + ".zip", "b".repeat(251) + ".zip",
                "\uD83D\uDCE6".repeat(110) + "가".repeat(30) + ".zip" }) {
            UUID sessionId = createAndUpload(adminToken, archiveName);
            MvcResult result = mvc.perform(post("/api/v1/upload-sessions/{id}/finalize", sessionId)
                    .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[0].originalFilename").value(archiveName)).andReturn();
            var payload = mapper.readTree(result.getResponse().getContentAsString());
            String title = payload.path("title").asText();
            assertThat(title.codePointCount(0, title.length())).isLessThanOrEqualTo(200);
            mvc.perform(get(payload.path("attachments").get(0).path("downloadUrl").asText()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(ZIP_BYTES));
        }
    }

    @Test
    void reusedUsernameDoesNotInheritPostsRepliesOrUploadSessions() throws Exception {
        Admin original = saveUser("reusedowner", UserRole.USER);
        String oldToken = jwt.generateToken(original.getUsername());
        long postId = createPost(oldToken, "original owner's post");
        MvcResult reply = mvc.perform(post("/api/v1/posts/{id}/replies", postId)
                .header("Authorization", bearer(oldToken)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("bodyBase64", encode(ZIP_BYTES)))))
            .andExpect(status().isCreated()).andReturn();
        long replyId = mapper.readTree(reply.getResponse().getContentAsString()).path("replies").get(0).path("id").asLong();
        UUID sessionId = createAndUpload(oldToken, "original-owner.zip");
        mvc.perform(delete("/api/v1/users/{id}", original.getId()).header("Authorization", bearer(adminToken)))
            .andExpect(status().isNoContent());
        saveUser("reusedowner", UserRole.USER);
        String newToken = jwt.generateToken("reusedowner");
        mvc.perform(delete("/api/v1/posts/{id}", postId).header("Authorization", bearer(newToken)))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/posts/replies/{id}", replyId).header("Authorization", bearer(newToken)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/posts/batch-delete").header("Authorization", bearer(newToken))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("ids", java.util.List.of(postId)))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/upload-sessions/{id}/finalize", sessionId).header("Authorization", bearer(newToken)))
            .andExpect(status().isUnauthorized());
        assertThat(posts.existsById(postId)).isTrue();
        assertThat(replies.existsById(replyId)).isTrue();
    }

    @Test
    void committedDeleteIsRetriedAfterFilesystemFailure() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/posts")
                .file(new MockMultipartFile("attachments", "retry.txt", "text/plain", ZIP_BYTES))
                .header("Authorization", bearer(adminToken)).param("title", "retry fixture"))
            .andExpect(status().isCreated()).andReturn();
        long postId = mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        var attachment = attachments.findByPost_IdOrderByCreatedAtAscIdAsc(postId).getFirst();
        String path = attachment.getStoragePath();
        org.mockito.Mockito.doThrow(new com.llm.app.board.exception.AttachmentStorageException("simulated I/O failure",
            new java.io.IOException("test fixture"))).when(storage).deleteIfExists(path);
        mvc.perform(delete("/api/v1/posts/{id}", postId).header("Authorization", bearer(adminToken)))
            .andExpect(status().isNoContent());
        assertThat(posts.existsById(postId)).isFalse();
        assertThat(deletions.existsById(path)).isTrue();
        assertThat(Files.exists(DATA_ROOT.resolve("attachments").resolve(path))).isTrue();
        org.mockito.Mockito.reset(storage);
        deletionWorker.retryPending();
        assertThat(deletions.existsById(path)).isFalse();
        assertThat(Files.exists(DATA_ROOT.resolve("attachments").resolve(path))).isFalse();
    }

    @Test
    void newFileIsRemovedWhenTransactionFailsAfterSavingItsRow() {
        var request = new com.llm.app.board.dto.CreateBoardPostRequest();
        request.setTitle("rollback new file");
        request.setAttachments(java.util.List.of(new MockMultipartFile("attachments", "new.txt", "text/plain", ZIP_BYTES)));
        var savedPath = new java.util.concurrent.atomic.AtomicReference<String>();
        var transaction = new TransactionTemplate(transactionManager);
        Long userId = users.findByUsername("reviewadmin").orElseThrow().getId();
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            var post = boardService.createPost(userId, request);
            savedPath.set(attachments.findByPost_IdOrderByCreatedAtAscIdAsc(post.id()).getFirst().getStoragePath());
            throw new IllegalStateException("failure after DB row save");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(posts.count()).isZero();
        assertThat(attachments.count()).isZero();
        assertThat(savedPath.get()).isNotNull();
        assertThat(Files.exists(DATA_ROOT.resolve("attachments").resolve(savedPath.get()))).isFalse();
    }

    private UUID createAndUpload(String token, String archiveName) throws Exception {
        String base64 = encode(ZIP_BYTES);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ZIP_BYTES));
        var request = new CreateUploadSessionRequest(archiveName, ZIP_BYTES.length, base64.length(), 1, sha);
        MvcResult create = mvc.perform(post("/api/v1/upload-sessions")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(wire.encodeCreateRequest(request))))
            .andExpect(status().isCreated()).andReturn();
        var response = mapper.readValue(create.getResponse().getContentAsString(), UploadSessionStatusResponse.class);
        UUID sessionId = wire.decodeStatus(response).sessionId();
        mvc.perform(post("/api/v1/upload-sessions/{id}/chunks", sessionId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(wire.encodeChunkRequest(1, base64))))
            .andExpect(status().isOk());
        return sessionId;
    }

    private long createPost(String token, String title) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/posts")
                .header("Authorization", bearer(token)).param("title", title))
            .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private Admin saveUser(String username, UserRole role) {
        Admin user = new Admin();
        user.setUsername(username);
        user.setPasswordHash("unused-test-hash");
        user.setRole(role);
        user.setCreatedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
}
