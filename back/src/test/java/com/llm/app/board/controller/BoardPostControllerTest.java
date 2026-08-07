package com.llm.app.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.auth.Admin;
import com.llm.app.auth.AdminRepository;
import com.llm.app.auth.JwtProvider;
import com.llm.app.auth.UserRole;
import com.llm.app.board.ai.AiProvider;
import com.llm.app.board.ai.AiReplyGenerator;
import com.llm.app.board.exception.AiProviderNotConfiguredException;
import com.llm.app.board.exception.AiReplyGenerationException;
import com.llm.app.board.dto.CreateUploadSessionRequest;
import com.llm.app.board.dto.UploadSessionStatusResponse;
import com.llm.app.board.model.BoardAttachment;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardPostMode;
import com.llm.app.board.model.BoardReply;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.service.UploadSessionStatusSnapshot;
import com.llm.app.board.service.UploadSessionWireCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoardPostControllerTest {
	private static final byte[] ZIP_BYTES = "PK\u0003demo-zip".getBytes(StandardCharsets.UTF_8);
	private static final String ZIP_SHA256 = "24d7a4ddff14fdb4675b6fc404a184b4cbbfb82e2bc5cad4c15b1e17de1a97f6";
	private static final int CHUNK_SIZE_BASE64_CHARS = 8;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UploadSessionWireCodec uploadSessionWireCodec;

	@Autowired
	private BoardPostRepository boardPostRepository;

	@Autowired
	private BoardReplyRepository boardReplyRepository;

	@Autowired
	private BoardAttachmentRepository boardAttachmentRepository;

	@Value("${app.attachments.root-path}")
	private String attachmentRootPath;

	@Autowired
	private JwtProvider jwtProvider;

	@Autowired
	private AdminRepository adminRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockBean
	private AiReplyGenerator aiReplyGenerator;

	private String token;
	private String memberToken;
	private String otherMemberToken;

	@BeforeEach
	void setUp() throws IOException {
		boardAttachmentRepository.deleteAll();
		boardReplyRepository.deleteAll();
		boardPostRepository.deleteAll();
		adminRepository.deleteAll();
		deleteRecursively(Path.of(attachmentRootPath));
		saveUser("admin", "adminpass", UserRole.ADMIN);
		saveUser("member1", "memberpass", UserRole.USER);
		saveUser("member2", "memberpass", UserRole.USER);
		token = jwtProvider.generateToken("admin");
		memberToken = jwtProvider.generateToken("member1");
		otherMemberToken = jwtProvider.generateToken("member2");
	}

	private Admin saveUser(String username, String password, UserRole role) {
		Admin admin = new Admin();
		admin.setUsername(username);
		admin.setPasswordHash(passwordEncoder.encode(password));
		admin.setRole(role);
		admin.setCreatedAt(Instant.now());
		return adminRepository.saveAndFlush(admin);
	}

	@Test
	void postAndReplyCrudShouldWork() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "첫 글")
				.param("bodyBase64", encode("첫 번째 게시글 본문")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id", notNullValue()))
			.andExpect(jsonPath("$.title").value("첫 글"))
			.andExpect(jsonPath("$.body").value("첫 번째 게시글 본문"))
			.andExpect(jsonPath("$.mode").value("NORMAL"))
			.andExpect(jsonPath("$.authorUsername").value("admin"))
			.andExpect(jsonPath("$.conversionReady").value(false))
			.andExpect(jsonPath("$.attachments", hasSize(0)))
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.totalPages").value(1))
			.andExpect(jsonPath("$.items[0].mode").value("NORMAL"))
			.andExpect(jsonPath("$.items[0].conversionReady").value(false))
			.andExpect(jsonPath("$.items[0].replyCount").value(0))
			.andExpect(jsonPath("$.items[0].hasAttachment").value(false));

		MvcResult replyResult = mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("첫 답변"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)))
			.andExpect(jsonPath("$.replies[0].body").value("첫 답변"))
			.andReturn();

		long replyId = extractFirstReplyId(replyResult.getResponse().getContentAsString());

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token)
				.param("title", "수정된 글")
				.param("bodyBase64", encode("수정된 본문")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("수정된 글"))
			.andExpect(jsonPath("$.body").value("수정된 본문"));

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("수정된 답변"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies[0].body").value("수정된 답변"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isNoContent());

		mockMvc.perform(delete("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/{id}", postId))
			.andExpect(status().isNotFound());
	}

	@Test
	void attachmentLifecycleShouldWork() throws Exception {
		MockMultipartFile firstAttachment = new MockMultipartFile(
			"attachments",
			"guide.txt",
			"text/plain",
			"첫 첨부".getBytes(StandardCharsets.UTF_8)
		);
		MockMultipartFile secondAttachment = new MockMultipartFile(
			"attachments",
			"notes.txt",
			"text/plain",
			"둘째 첨부".getBytes(StandardCharsets.UTF_8)
		);

		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.file(firstAttachment)
				.file(secondAttachment)
				.header("Authorization", "Bearer " + token)
				.param("title", "첨부 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments", hasSize(2)))
			.andExpect(jsonPath("$.attachments[0].originalFilename").value("guide.txt"))
			.andExpect(jsonPath("$.attachments[1].originalFilename").value("notes.txt"))
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		JsonNode attachments = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("attachments");
		long firstAttachmentId = attachments.get(0).path("id").asLong();
		String firstDownloadUrl = attachments.get(0).path("downloadUrl").asText();
		assertThat(firstDownloadUrl).isEqualTo("/api/v1/posts/" + postId + "/attachments/" + firstAttachmentId);

		assertThat(boardAttachmentRepository.findByPost_IdOrderByCreatedAtAscIdAsc(postId)).hasSize(2);

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value("첨부 글"))
			.andExpect(jsonPath("$.items[0].hasAttachment").value(true));

		mockMvc.perform(get(firstDownloadUrl))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(content().bytes("첫 첨부".getBytes(StandardCharsets.UTF_8)));

		// 첫 첨부는 삭제하고 새 첨부를 추가한다(치환이 아니라 부분 삭제 + 추가).
		MockMultipartFile thirdAttachment = new MockMultipartFile(
			"attachments",
			"extra.txt",
			"text/plain",
			"추가 첨부".getBytes(StandardCharsets.UTF_8)
		);

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.file(thirdAttachment)
				.header("Authorization", "Bearer " + token)
				.param("title", "첨부 글 수정")
				.param("bodyBase64", encode("본문 수정"))
				.param("removeAttachmentIds", String.valueOf(firstAttachmentId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments", hasSize(2)))
			.andExpect(jsonPath("$.body").value("본문 수정"));

		assertThat(boardAttachmentRepository.findByIdAndPost_Id(firstAttachmentId, postId)).isEmpty();
		assertThat(boardAttachmentRepository.findByPost_IdOrderByCreatedAtAscIdAsc(postId)).hasSize(2);

		mockMvc.perform(get("/api/v1/posts/{postId}/attachments/{attachmentId}", postId, firstAttachmentId))
			.andExpect(status().isNotFound());
	}

	@Test
	void deletingPostShouldDeleteAttachmentMetadataAndFile() throws Exception {
		MockMultipartFile attachment = new MockMultipartFile(
			"attachments",
			"delete.txt",
			"text/plain",
			"삭제 파일".getBytes(StandardCharsets.UTF_8)
		);

		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.file(attachment)
				.header("Authorization", "Bearer " + token)
				.param("title", "삭제 첨부")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		BoardAttachment savedAttachment = boardAttachmentRepository.findByPost_IdOrderByCreatedAtAscIdAsc(postId).get(0);
		Path attachmentPath = Path.of(attachmentRootPath).resolve(savedAttachment.getStoragePath());
		assertThat(Files.exists(attachmentPath)).isTrue();

		mockMvc.perform(delete("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isNoContent());

		assertThat(boardAttachmentRepository.findByPost_IdOrderByCreatedAtAscIdAsc(postId)).isEmpty();
		assertThat(Files.exists(attachmentPath)).isFalse();
	}

	@Test
	void invalidBase64ShouldReturnBadRequest() throws Exception {
		mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "bad")
				.param("bodyBase64", "%%%bad%%%"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ENCODED_BODY"));
	}

	@Test
	void createPostShouldAcceptMissingBody() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "본문 없는 글"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("본문 없는 글"))
			.andExpect(jsonPath("$.body").value(""))
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		assertThat(boardPostRepository.findById(postId)).isPresent();
		assertThat(boardPostRepository.findById(postId).orElseThrow().getBody()).isEmpty();

		mockMvc.perform(get("/api/v1/posts/{id}", postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value(""));
	}

	@Test
	void updatePostShouldAcceptEmptyBody() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "본문 있는 글")
				.param("bodyBase64", encode("기존 본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token)
				.param("title", "본문 지운 글")
				.param("bodyBase64", ""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("본문 지운 글"))
			.andExpect(jsonPath("$.body").value(""));

		assertThat(boardPostRepository.findById(postId)).isPresent();
		assertThat(boardPostRepository.findById(postId).orElseThrow().getBody()).isEmpty();
	}

	@Test
	void shouldSearchPostsByTitle() throws Exception {
		mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "스프링 질문")
				.param("bodyBase64", encode("JPA 페이징이 궁금합니다")))
			.andExpect(status().isCreated());

		mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "리액트 질문")
				.param("bodyBase64", encode("UI를 만들고 싶어요")))
			.andExpect(status().isCreated());

		mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "잡담")
				.param("bodyBase64", encode("오늘 날씨가 좋네요")))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/api/v1/posts").queryParam("query", "질문"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.totalItems").value(2));

		mockMvc.perform(get("/api/v1/posts").queryParam("query", "리액트"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].title").value("리액트 질문"))
			.andExpect(jsonPath("$.totalItems").value(1));

		mockMvc.perform(get("/api/v1/posts").queryParam("query", "없는 검색어"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalItems").value(0))
			.andExpect(jsonPath("$.totalPages").value(0));
	}

	@Test
	void manualFileConversionRequestShouldBeRejectedOnCreate() throws Exception {
		MockMultipartFile attachment = new MockMultipartFile(
			"attachments",
			"source.zip",
			"application/zip",
			"zip".getBytes(StandardCharsets.UTF_8)
		);

		mockMvc.perform(multipartPost("/api/v1/posts")
				.file(attachment)
				.header("Authorization", "Bearer " + token)
				.param("title", "zip 요청")
				.param("mode", "FILE_CONVERSION_REQUEST")
				.param("bodyBase64", Base64.getEncoder().encodeToString("zip".getBytes(StandardCharsets.UTF_8))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_FILE_CONVERSION_REQUEST"));
	}

	@Test
	void manualFileConversionRequestShouldBeRejectedOnUpdate() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "일반 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token)
				.param("title", "변경 시도")
				.param("mode", "FILE_CONVERSION_REQUEST")
				.param("bodyBase64", Base64.getEncoder().encodeToString("zip".getBytes(StandardCharsets.UTF_8))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_FILE_CONVERSION_REQUEST"));
	}

	@Test
	void createUploadSessionShouldSucceed() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/upload-sessions")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createUploadSessionRequest("bundle.zip", 11, 8, 2, ZIP_SHA256)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.A6", notNullValue()))
			.andExpect(jsonPath("$.archiveName").doesNotExist())
			.andReturn();
		UploadSessionStatusSnapshot snapshot = readUploadSessionStatus(result);
		assertThat(snapshot.archiveName()).isEqualTo("bundle.zip");
		assertThat(snapshot.fileSizeBytes()).isEqualTo(11);
		assertThat(snapshot.chunkSizeBase64Chars()).isEqualTo(8);
		assertThat(snapshot.totalChunks()).isEqualTo(2);
		assertThat(snapshot.uploadedChunks()).isEmpty();
		assertThat(snapshot.complete()).isFalse();
	}

	@Test
	void uploadSessionPartShouldSucceed() throws Exception {
		String encoded = encode(ZIP_BYTES);
		UUID sessionId = createUploadSession("parts.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, ZIP_SHA256);

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadSessionChunkRequest(1, encoded.substring(0, CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.A6").exists())
			.andExpect(jsonPath("$.sessionId").doesNotExist());
	}

	@Test
	void finalizeUploadSessionShouldCreateFileConversionRequestPostAndAttachment() throws Exception {
		String encoded = encode(ZIP_BYTES);
		UUID sessionId = createUploadSession("bundle.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, ZIP_SHA256);

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadSessionChunkRequest(1, encoded.substring(0, CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadSessionChunkRequest(2, encoded.substring(CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk());

		MvcResult finalizeResult = mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/finalize", sessionId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("FILE_CONVERSION_REQUEST"))
			.andExpect(jsonPath("$.conversionReady").value(true))
			.andExpect(jsonPath("$.attachments", hasSize(1)))
			.andExpect(jsonPath("$.attachments[0].originalFilename").value("bundle.zip"))
			.andExpect(jsonPath("$.body").value(containsString("원본 파일명: bundle.zip")))
			.andReturn();

		String downloadUrl = objectMapper.readTree(finalizeResult.getResponse().getContentAsString())
			.path("attachments").get(0).path("downloadUrl").asText();

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].mode").value("FILE_CONVERSION_REQUEST"))
			.andExpect(jsonPath("$.items[0].conversionReady").value(true))
			.andExpect(jsonPath("$.items[0].hasAttachment").value(true));

		mockMvc.perform(get(downloadUrl))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("bundle.zip")))
			.andExpect(content().bytes(ZIP_BYTES));
	}

	@Test
	void finalizeUploadSessionShouldFailWhenPartsAreMissing() throws Exception {
		String encoded = encode(ZIP_BYTES);
		UUID sessionId = createUploadSession("missing.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, ZIP_SHA256);

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadSessionChunkRequest(1, encoded.substring(0, CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/finalize", sessionId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_UPLOAD_SESSION_REQUEST"));
	}

	@Test
	void bodyShouldPreserveLeadingTrailingWhitespace() throws Exception {
		String originalBody = "  첫 줄\n둘째 줄  \n";

		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "공백 보존")
				.param("bodyBase64", encode(originalBody)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value(originalBody))
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		assertThat(boardPostRepository.findById(postId)).isPresent();
		assertThat(boardPostRepository.findById(postId).orElseThrow().getBody()).isEqualTo(originalBody);
	}

	@Test
	void bodyLongerThan20000ShouldStillBeAccepted() throws Exception {
		String largeBody = "a".repeat(30_000);
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "긴 본문")
				.param("bodyBase64", encode(largeBody)))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		assertThat(boardPostRepository.findById(postId)).isPresent();
		assertThat(boardPostRepository.findById(postId).orElseThrow().getBody()).hasSize(30_000);

		mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode(largeBody))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)));

		assertThat(boardReplyRepository.findAll()).hasSize(1);
		assertThat(boardReplyRepository.findAll().get(0).getBody()).hasSize(30_000);
	}

	@Test
	void bodyLongerThanOneMillionShouldReturnBadRequest() throws Exception {
		String tooLargeBody = "a".repeat(1_000_001);

		mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "너무 긴 본문")
				.param("bodyBase64", encode(tooLargeBody)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ENCODED_BODY"));
	}

	@Test
	void tooLargeAttachmentShouldReturnPayloadTooLarge() throws Exception {
		byte[] largeAttachment = new byte[2 * 1024 * 1024 + 1];
		MockMultipartFile attachment = new MockMultipartFile(
			"attachments",
			"big.bin",
			"application/octet-stream",
			largeAttachment
		);

		mockMvc.perform(multipartPost("/api/v1/posts")
				.file(attachment)
				.header("Authorization", "Bearer " + token)
				.param("title", "큰 파일")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
	}

	@Test
	void exceedingMaxAttachmentsShouldReturnBadRequest() throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipartPost("/api/v1/posts");
		request.header("Authorization", "Bearer " + token)
			.param("title", "첨부 6개")
			.param("bodyBase64", encode("본문"));
		for (int i = 1; i <= 6; i++) {
			request.file(new MockMultipartFile(
				"attachments",
				"file" + i + ".txt",
				"text/plain",
				("내용" + i).getBytes(StandardCharsets.UTF_8)
			));
		}

		mockMvc.perform(request)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT_REQUEST"));
	}

	@Test
	void removingUnknownAttachmentShouldReturnBadRequest() throws Exception {
		MockMultipartFile attachment = new MockMultipartFile(
			"attachments",
			"keep.txt",
			"text/plain",
			"유지".getBytes(StandardCharsets.UTF_8)
		);
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.file(attachment)
				.header("Authorization", "Bearer " + token)
				.param("title", "첨부 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token)
				.param("title", "첨부 글")
				.param("bodyBase64", encode("본문"))
				.param("removeAttachmentIds", "999999"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT_REQUEST"));
	}

	@Test
	void overCapUpdateShouldRejectWithoutDeletingExistingFiles() throws Exception {
		// 첨부 5개로 생성(상한)
		MockMultipartHttpServletRequestBuilder createRequest = multipartPost("/api/v1/posts");
		createRequest.header("Authorization", "Bearer " + token)
			.param("title", "다중 첨부")
			.param("bodyBase64", encode("본문"));
		for (int i = 1; i <= 5; i++) {
			createRequest.file(new MockMultipartFile(
				"attachments",
				"f" + i + ".txt",
				"text/plain",
				("내용" + i).getBytes(StandardCharsets.UTF_8)
			));
		}
		MvcResult createResult = mockMvc.perform(createRequest)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments", hasSize(5)))
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		JsonNode attachments = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("attachments");
		long firstAttachmentId = attachments.get(0).path("id").asLong();
		String firstDownloadUrl = attachments.get(0).path("downloadUrl").asText();

		// 유효 1개 삭제 + 신규 2개 추가 → 5 - 1 + 2 = 6 > 5 → 400 (삭제·저장보다 검증이 먼저)
		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.file(new MockMultipartFile("attachments", "new1.txt", "text/plain", "신규1".getBytes(StandardCharsets.UTF_8)))
				.file(new MockMultipartFile("attachments", "new2.txt", "text/plain", "신규2".getBytes(StandardCharsets.UTF_8)))
				.header("Authorization", "Bearer " + token)
				.param("title", "다중 첨부")
				.param("bodyBase64", encode("본문"))
				.param("removeAttachmentIds", String.valueOf(firstAttachmentId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT_REQUEST"));

		// 거부됐으므로 기존 5개 그대로 유지되고, 삭제하려던 첨부의 실제 파일도 보존되어 다운로드 가능해야 한다.
		assertThat(boardAttachmentRepository.findByPost_IdOrderByCreatedAtAscIdAsc(postId)).hasSize(5);
		mockMvc.perform(get(firstDownloadUrl))
			.andExpect(status().isOk())
			.andExpect(content().bytes("내용1".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void postsShouldBePaginatedByTenItems() throws Exception {
		Instant baseTime = Instant.parse("2026-03-11T00:00:00Z");
		BoardPost latestPost = null;
		BoardPost twelfthPost = null;
		for (int i = 1; i <= 21; i++) {
			BoardPostMode mode = i == 21 ? BoardPostMode.FILE_CONVERSION_REQUEST : BoardPostMode.NORMAL;
			BoardPost savedPost = boardPostRepository.save(new BoardPost(
				"글 " + i,
				"본문 " + i,
				mode,
				"admin",
				baseTime.plusSeconds(i),
				baseTime.plusSeconds(i)
			));
			if (i == 21) {
				latestPost = savedPost;
			}
			if (i == 12) {
				twelfthPost = savedPost;
			}
		}
		boardPostRepository.flush();
		boardReplyRepository.save(new BoardReply(latestPost, "답변 A", "admin", baseTime.plusSeconds(30), baseTime.plusSeconds(30)));
		boardReplyRepository.save(new BoardReply(latestPost, "답변 B", "admin", baseTime.plusSeconds(31), baseTime.plusSeconds(31)));
		boardReplyRepository.save(new BoardReply(twelfthPost, "답변 C", "admin", baseTime.plusSeconds(32), baseTime.plusSeconds(32)));
		boardAttachmentRepository.save(new BoardAttachment(
			latestPost,
			"latest.txt",
			"latest-stored.txt",
			"2026/03/latest-stored.txt",
			"text/plain",
			128,
			baseTime.plusSeconds(40)
		));
		boardReplyRepository.flush();
		boardAttachmentRepository.flush();

		mockMvc.perform(get("/api/v1/posts").param("page", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(10)))
			.andExpect(jsonPath("$.items[0].title").value("글 21"))
			.andExpect(jsonPath("$.items[0].mode").value("FILE_CONVERSION_REQUEST"))
			.andExpect(jsonPath("$.items[0].conversionReady").value(true))
			.andExpect(jsonPath("$.items[0].replyCount").value(2))
			.andExpect(jsonPath("$.items[0].hasAttachment").value(true))
			.andExpect(jsonPath("$.items[9].title").value("글 12"))
			.andExpect(jsonPath("$.items[9].mode").value("NORMAL"))
			.andExpect(jsonPath("$.items[9].conversionReady").value(false))
			.andExpect(jsonPath("$.items[9].replyCount").value(1))
			.andExpect(jsonPath("$.items[9].hasAttachment").value(false))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(21))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.hasPrevious").value(false))
			.andExpect(jsonPath("$.hasNext").value(true));

		mockMvc.perform(get("/api/v1/posts").param("page", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(10)))
			.andExpect(jsonPath("$.items[0].title").value("글 11"))
			.andExpect(jsonPath("$.items[9].title").value("글 2"))
			.andExpect(jsonPath("$.page").value(2))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(21))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.hasPrevious").value(true))
			.andExpect(jsonPath("$.hasNext").value(true));

		mockMvc.perform(get("/api/v1/posts").param("page", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].title").value("글 1"))
			.andExpect(jsonPath("$.page").value(3))
			.andExpect(jsonPath("$.pageSize").value(10))
			.andExpect(jsonPath("$.totalItems").value(21))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.hasPrevious").value(true))
			.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	void aiReplyShouldBeStoredAndLocked() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "AI 테스트")
				.param("bodyBase64", encode("AI가 답변할 본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());
		given(aiReplyGenerator.generateReply(eq(AiProvider.GPT), anyString(), anyString()))
			.willReturn(new AiReplyGenerator.AiReplyResult("AI 생성 답변", "gpt-5.5"));

		MvcResult aiReplyResult = mockMvc.perform(post("/api/v1/posts/{id}/ai-replies", postId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "GPT"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies", hasSize(1)))
			.andExpect(jsonPath("$.replies[0].body").value("AI 생성 답변"))
			.andExpect(jsonPath("$.replies[0].ai").value(true))
			.andExpect(jsonPath("$.replies[0].aiProvider").value("GPT"))
			.andReturn();

		long replyId = extractFirstReplyId(aiReplyResult.getResponse().getContentAsString());

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("수정 시도"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AI_REPLY_LOCKED"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AI_REPLY_LOCKED"));
	}

	@Test
	void invalidAiProviderShouldReturnBadRequest() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "AI 공급자 테스트")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(post("/api/v1/posts/{id}/ai-replies", postId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "BARD"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AI_PROVIDER"));
	}

	@Test
	void aiReplyShouldBeRejectedForFileConversionRequestPost() throws Exception {
		Instant now = Instant.parse("2026-03-11T00:00:00Z");
		BoardPost post = boardPostRepository.save(new BoardPost(
			"변환 요청",
			"자동 업로드 생성 게시글입니다.",
			BoardPostMode.FILE_CONVERSION_REQUEST,
			"admin",
			now,
			now
		));
		boardAttachmentRepository.save(new BoardAttachment(
			post,
			"post.zip",
			"stored-post.zip",
			"stored-post.zip",
			"application/zip",
			128,
			now
		));
		boardAttachmentRepository.flush();

		mockMvc.perform(post("/api/v1/posts/{id}/ai-replies", post.getId())
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "GPT"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AI_REPLY_NOT_ALLOWED"));
	}

	@Test
	void batchDeleteWithTokenShouldDeletePosts() throws Exception {
		MvcResult result1 = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "배치 삭제 글 1")
				.param("bodyBase64", encode("본문 1")))
			.andExpect(status().isCreated())
			.andReturn();

		MvcResult result2 = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "배치 삭제 글 2")
				.param("bodyBase64", encode("본문 2")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId1 = extractId(result1.getResponse().getContentAsString());
		long postId2 = extractId(result2.getResponse().getContentAsString());

		mockMvc.perform(post("/api/v1/posts/batch-delete")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ids": [%d, %d]
					}
					""".formatted(postId1, postId2)))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void batchDeleteWithoutTokenShouldReturn401() throws Exception {
		mockMvc.perform(post("/api/v1/posts/batch-delete")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ids": [1, 2]
					}
					"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void createPostWithoutTokenShouldReturn401() throws Exception {
		mockMvc.perform(multipartPost("/api/v1/posts")
				.param("title", "무단 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void deletePostWithoutTokenShouldReturn401() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + token)
				.param("title", "삭제 대상 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(delete("/api/v1/posts/{id}", postId))
			.andExpect(status().isUnauthorized());
	}


	@Test
	void createPostShouldPersistAuthorUsername() throws Exception {
		mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "멤버 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorUsername").value("member1"));

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].authorUsername").value("member1"));
	}

	@Test
	void authorCanUpdateAndDeleteOwnPost() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "내 글")
				.param("bodyBase64", encode("원본")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "수정된 내 글")
				.param("bodyBase64", encode("수정 본문")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("수정된 내 글"))
			.andExpect(jsonPath("$.authorUsername").value("member1"));

		mockMvc.perform(delete("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + memberToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/{id}", postId))
			.andExpect(status().isNotFound());
	}

	@Test
	void nonAuthorCannotUpdateOrDeletePost() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "멤버1 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + otherMemberToken)
				.param("title", "가로채기")
				.param("bodyBase64", encode("불가")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(delete("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + otherMemberToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(get("/api/v1/posts/{id}", postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("멤버1 글"));
	}

	@Test
	void adminCanUpdateAndDeleteOthersPost() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "멤버 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token)
				.param("title", "관리자 수정")
				.param("bodyBase64", encode("관리자 본문")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("관리자 수정"))
			.andExpect(jsonPath("$.authorUsername").value("member1"));

		mockMvc.perform(delete("/api/v1/posts/{id}", postId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isNoContent());
	}

	@Test
	void batchDeleteRejectsWhenNonOwnerIncludesOthersPost() throws Exception {
		MvcResult ownResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "내 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		MvcResult otherResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + otherMemberToken)
				.param("title", "남의 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long ownId = extractId(ownResult.getResponse().getContentAsString());
		long otherId = extractId(otherResult.getResponse().getContentAsString());

		mockMvc.perform(post("/api/v1/posts/batch-delete")
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ids": [%d, %d]
					}
					""".formatted(ownId, otherId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		// 트랜잭션 롤백으로 둘 다 남아야 한다.
		mockMvc.perform(get("/api/v1/posts/{id}", ownId))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/posts/{id}", otherId))
			.andExpect(status().isOk());
	}


	@Test
	void updateMissingPostShouldReturn404() throws Exception {
		mockMvc.perform(multipartPut("/api/v1/posts/{id}", 99999L)
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "없는 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void deleteMissingPostShouldReturn404() throws Exception {
		mockMvc.perform(delete("/api/v1/posts/{id}", 99999L)
				.header("Authorization", "Bearer " + memberToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void updateMissingReplyShouldReturn404() throws Exception {
		mockMvc.perform(put("/api/v1/posts/replies/{id}", 99999L)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("본문"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void deleteMissingReplyShouldReturn404() throws Exception {
		mockMvc.perform(delete("/api/v1/posts/replies/{id}", 99999L)
				.header("Authorization", "Bearer " + memberToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void aiReplyGenerationFailureShouldReturn502() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "AI 실패 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		given(aiReplyGenerator.generateReply(eq(AiProvider.GPT), anyString(), anyString()))
			.willThrow(new AiReplyGenerationException("external api boom"));

		mockMvc.perform(post("/api/v1/posts/{id}/ai-replies", postId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "GPT"
					}
					"""))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("AI_REPLY_GENERATION_FAILED"));
	}

	@Test
	void aiReplyWhenProviderNotConfiguredShouldReturn503() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "AI 미설정 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		given(aiReplyGenerator.generateReply(eq(AiProvider.GPT), anyString(), anyString()))
			.willThrow(new AiProviderNotConfiguredException("GPT"));

		mockMvc.perform(post("/api/v1/posts/{id}/ai-replies", postId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "provider": "GPT"
					}
					"""))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_PROVIDER_NOT_CONFIGURED"));
	}

	@Test
	void replyBodyLongerThanOneMillionShouldReturnBadRequest() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "댓글 길이 제한 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		String hugeBody = encode("a".repeat(1_000_001));
		mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(hugeBody)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ENCODED_BODY"));
	}

	@Test
	void adminCanBatchDeleteOtherUsersPosts() throws Exception {
		MvcResult memberPost = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "멤버 글 1")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		MvcResult otherPost = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + otherMemberToken)
				.param("title", "멤버 글 2")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();

		long memberId = extractId(memberPost.getResponse().getContentAsString());
		long otherId = extractId(otherPost.getResponse().getContentAsString());

		mockMvc.perform(post("/api/v1/posts/batch-delete")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ids": [%d, %d]
					}
					""".formatted(memberId, otherId)))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(0));
	}


	@Test
	void createReplyShouldPersistAuthorUsername() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "댓글 작성자 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("내 댓글"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies[0].body").value("내 댓글"))
			.andExpect(jsonPath("$.replies[0].authorUsername").value("member1"));
	}

	@Test
	void replyAuthorCanUpdateAndDeleteOwnReply() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "댓글 소유 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		MvcResult replyResult = mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("원본 댓글"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.replies[0].authorUsername").value("member1"))
			.andReturn();
		long replyId = extractFirstReplyId(replyResult.getResponse().getContentAsString());

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("수정된 댓글"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies[0].body").value("수정된 댓글"))
			.andExpect(jsonPath("$.replies[0].authorUsername").value("member1"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + memberToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/{id}", postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies", hasSize(0)));
	}

	@Test
	void nonReplyAuthorCannotUpdateOrDeleteReply() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "댓글 권한 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		MvcResult replyResult = mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("멤버1의 댓글"))))
			.andExpect(status().isCreated())
			.andReturn();
		long replyId = extractFirstReplyId(replyResult.getResponse().getContentAsString());

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + otherMemberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("가로채기"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + otherMemberToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(get("/api/v1/posts/{id}", postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies[0].body").value("멤버1의 댓글"));
	}

	@Test
	void adminCanUpdateAndDeleteOthersReply() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "관리자 댓글 권한 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		MvcResult replyResult = mockMvc.perform(post("/api/v1/posts/{id}/replies", postId)
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("멤버 댓글"))))
			.andExpect(status().isCreated())
			.andReturn();
		long replyId = extractFirstReplyId(replyResult.getResponse().getContentAsString());

		mockMvc.perform(put("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("관리자 수정"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies[0].body").value("관리자 수정"))
			.andExpect(jsonPath("$.replies[0].authorUsername").value("member1"));

		mockMvc.perform(delete("/api/v1/posts/replies/{id}", replyId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isNoContent());
	}

	@Test
	void legacyReplyWithoutAuthorCanBeManagedOnlyByAdmin() throws Exception {
		MvcResult createResult = mockMvc.perform(multipartPost("/api/v1/posts")
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "레거시 댓글 글")
				.param("bodyBase64", encode("본문")))
			.andExpect(status().isCreated())
			.andReturn();
		long postId = extractId(createResult.getResponse().getContentAsString());

		Instant now = Instant.now();
		BoardPost post = boardPostRepository.findWithRepliesById(postId).orElseThrow();
		BoardReply legacy = new BoardReply(
			post,
			"레거시 댓글",
			null,
			now,
			now
		);
		// 게시글의 replies 컬렉션에도 추가해야 같은 트랜잭션 재조회에서 응답에 포함된다.
		post.getReplies().add(legacy);
		boardReplyRepository.saveAndFlush(legacy);

		mockMvc.perform(put("/api/v1/posts/replies/{id}", legacy.getId())
				.header("Authorization", "Bearer " + memberToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("멤버 수정 시도"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(put("/api/v1/posts/replies/{id}", legacy.getId())
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "bodyBase64": "%s"
					}
					""".formatted(encode("관리자 수정"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies[0].body").value("관리자 수정"));
	}

	@Test
	void adminCanBatchDeleteLegacyPosts() throws Exception {
		Instant now = Instant.parse("2026-03-11T00:00:00Z");
		BoardPost legacy = boardPostRepository.save(new BoardPost(
			"레거시 배치 삭제",
			"작성자 없음",
			BoardPostMode.NORMAL,
			null,
			now,
			now
		));
		boardPostRepository.flush();

		mockMvc.perform(post("/api/v1/posts/batch-delete")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ids": [%d]
					}
					""".formatted(legacy.getId())))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void legacyPostWithoutAuthorCanBeManagedOnlyByAdmin() throws Exception {
		Instant now = Instant.parse("2026-03-11T00:00:00Z");
		BoardPost legacy = boardPostRepository.save(new BoardPost(
			"레거시 글",
			"작성자 없음",
			BoardPostMode.NORMAL,
			null,
			now,
			now
		));
		boardPostRepository.flush();

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", legacy.getId())
				.header("Authorization", "Bearer " + memberToken)
				.param("title", "멤버 수정 시도")
				.param("bodyBase64", encode("불가")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(multipartPut("/api/v1/posts/{id}", legacy.getId())
				.header("Authorization", "Bearer " + token)
				.param("title", "관리자 레거시 수정")
				.param("bodyBase64", encode("가능")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("관리자 레거시 수정"));
	}

	private String encode(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private String encode(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private long extractId(String responseBody) {
		try {
			return objectMapper.readTree(responseBody).path("id").asLong();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to extract post id", exception);
		}
	}

	private UUID createUploadSession(String archiveName, int fileSizeBytes, int chunkSizeBase64Chars, int totalChunks, String fileSha256) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/upload-sessions")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createUploadSessionRequest(archiveName, fileSizeBytes, chunkSizeBase64Chars, totalChunks, fileSha256)))
			.andExpect(status().isCreated())
			.andReturn();
		return readUploadSessionStatus(result).sessionId();
	}

	private String uploadSessionChunkRequest(int chunkNumber, String chunkDataBase64) {
		try {
			return objectMapper.writeValueAsString(uploadSessionWireCodec.encodeChunkRequest(chunkNumber, chunkDataBase64));
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to write upload-session chunk request", exception);
		}
	}

	private byte[] slice(byte[] bytes, int startInclusive, int endExclusive) {
		byte[] slice = new byte[endExclusive - startInclusive];
		System.arraycopy(bytes, startInclusive, slice, 0, slice.length);
		return slice;
	}

	private long extractFirstReplyId(String responseBody) {
		try {
			return objectMapper.readTree(responseBody).path("replies").get(0).path("id").asLong();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to extract reply id", exception);
		}
	}

	private MockMultipartHttpServletRequestBuilder multipartPost(String uriTemplate, Object... uriVariables) {
		return multipart(uriTemplate, uriVariables);
	}

	private MockMultipartHttpServletRequestBuilder multipartPut(String uriTemplate, Object... uriVariables) {
		MockMultipartHttpServletRequestBuilder builder = multipart(uriTemplate, uriVariables);
		builder.with(request -> {
			request.setMethod("PUT");
			return request;
		});
		return builder;
	}

	private void deleteRecursively(Path rootPath) throws IOException {
		if (!Files.exists(rootPath)) {
			return;
		}

		try (var paths = Files.walk(rootPath)) {
			paths.sorted(Comparator.reverseOrder())
				.forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException exception) {
						throw new IllegalStateException("Failed to delete " + path, exception);
					}
				});
		}
	}

	private String createUploadSessionRequest(String archiveName, int fileSizeBytes, int chunkSizeBase64Chars, int totalChunks, String fileSha256) throws Exception {
		return objectMapper.writeValueAsString(uploadSessionWireCodec.encodeCreateRequest(
			new CreateUploadSessionRequest(archiveName, fileSizeBytes, chunkSizeBase64Chars, totalChunks, fileSha256)
		));
	}

	private UploadSessionStatusSnapshot readUploadSessionStatus(MvcResult result) throws Exception {
		UploadSessionStatusResponse response = objectMapper.readValue(
			result.getResponse().getContentAsString(),
			UploadSessionStatusResponse.class
		);
		return uploadSessionWireCodec.decodeStatus(response);
	}
}
