package com.llm.app.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llm.app.auth.JwtProvider;
import com.llm.app.board.ai.AiReplyGenerator;
import com.llm.app.board.dto.CreateUploadSessionRequest;
import com.llm.app.board.dto.EncryptedUploadSessionCreateRequest;
import com.llm.app.board.dto.EncryptedUploadSessionChunkUploadRequest;
import com.llm.app.board.dto.UploadSessionStatusResponse;
import com.llm.app.board.model.UploadSessionStatus;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.repository.UploadSessionPartRepository;
import com.llm.app.board.repository.UploadSessionRepository;
import com.llm.app.board.service.UploadSessionStatusSnapshot;
import com.llm.app.board.service.UploadSessionWireCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class UploadSessionControllerTest {
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
	private JwtProvider jwtProvider;

	@Autowired
	private BoardPostRepository boardPostRepository;

	@Autowired
	private BoardReplyRepository boardReplyRepository;

	@Autowired
	private BoardAttachmentRepository boardAttachmentRepository;

	@Autowired
	private UploadSessionRepository uploadSessionRepository;

	@Autowired
	private UploadSessionPartRepository uploadSessionPartRepository;

	@Value("${app.attachments.root-path}")
	private String attachmentRootPath;

	@Value("${app.upload-sessions.root-path}")
	private String uploadSessionRootPath;

	@MockBean
	private AiReplyGenerator aiReplyGenerator;

	private String token;

	@BeforeEach
	void setUp() throws IOException {
		boardAttachmentRepository.deleteAll();
		boardReplyRepository.deleteAll();
		boardPostRepository.deleteAll();
		uploadSessionPartRepository.deleteAll();
		uploadSessionRepository.deleteAll();
		deleteRecursively(Path.of(attachmentRootPath));
		deleteRecursively(Path.of(uploadSessionRootPath));
		token = jwtProvider.generateToken("admin");
	}

	@Test
	void uploadSessionLifecycleShouldCreateAttachmentBackedPost() throws Exception {
		UUID sessionId = createSession("bundle.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, ZIP_SHA256);
		String encoded = encode(ZIP_BYTES);

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(1, encoded.substring(0, CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.A1").exists())
			.andExpect(jsonPath("$.archiveName").doesNotExist())
			.andExpect(jsonPath("$.A6").value(not("bundle.zip")));

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(2, encoded.substring(CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.A7").exists())
			.andExpect(jsonPath("$.A8").exists());

		UploadSessionStatusSnapshot snapshot = readStatus(mockMvc.perform(get("/api/v1/upload-sessions/{sessionId}", sessionId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.A1").exists())
			.andExpect(jsonPath("$.archiveName").doesNotExist())
			.andReturn());
		assertThat(snapshot.archiveName()).isEqualTo("bundle.zip");
		assertThat(snapshot.fileSizeBytes()).isEqualTo(ZIP_BYTES.length);
		assertThat(snapshot.chunkSizeBase64Chars()).isEqualTo(CHUNK_SIZE_BASE64_CHARS);
		assertThat(snapshot.totalChunks()).isEqualTo(2);
		assertThat(snapshot.uploadedChunks()).containsExactly(1, 2);
		assertThat(snapshot.complete()).isTrue();

		MvcResult finalizeResult = mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/finalize", sessionId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("FILE_CONVERSION_REQUEST"))
			.andExpect(jsonPath("$.conversionReady").value(true))
			.andExpect(jsonPath("$.attachment.originalFilename").value("bundle.zip"))
			.andExpect(jsonPath("$.title").value("[bundle.zip] 업로드 완료"))
			.andExpect(jsonPath("$.body").value(containsString("SHA-256 검증: 성공")))
			.andReturn();

		long postId = extractId(finalizeResult.getResponse().getContentAsString());

		mockMvc.perform(get("/api/v1/posts/{id}/attachment", postId))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("bundle.zip")))
			.andExpect(content().bytes(ZIP_BYTES));

		assertThat(uploadSessionRepository.count()).isZero();
		assertThat(uploadSessionPartRepository.count()).isZero();
		assertThat(Files.exists(Path.of(uploadSessionRootPath).resolve(sessionId.toString()))).isFalse();
	}

	@Test
	void finalizeShouldFailWhenChunksAreMissing() throws Exception {
		UUID sessionId = createSession("missing.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, ZIP_SHA256);
		String encoded = encode(ZIP_BYTES);

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(1, encoded.substring(0, CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/finalize", sessionId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_UPLOAD_SESSION_REQUEST"));
	}

	@Test
	void duplicateChunkUploadShouldBeIdempotent() throws Exception {
		UUID sessionId = createSession("dup.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, ZIP_SHA256);
		String encoded = encode(ZIP_BYTES);
		String firstChunk = encoded.substring(0, CHUNK_SIZE_BASE64_CHARS);

		MvcResult firstResult = mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(1, firstChunk)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.A7").exists())
			.andReturn();
		assertThat(readStatus(firstResult).uploadedChunks()).containsExactly(1);

		MvcResult duplicateResult = mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(1, firstChunk)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.A7").exists())
			.andReturn();
		assertThat(readStatus(duplicateResult).uploadedChunks()).containsExactly(1);
	}

	@Test
	void tamperedCiphertextShouldBeRejected() throws Exception {
		String request = createSessionRequest("tampered.zip", 11, 8, 2, ZIP_SHA256);
		ObjectNode node = (ObjectNode) objectMapper.readTree(request);
		node.put("A1", tamperCiphertext(node.get("A1").asText()));

		mockMvc.perform(post("/api/v1/upload-sessions")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(node)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_UPLOAD_SESSION_REQUEST"));
	}

	@Test
	void requestEncryptedWithDifferentSecretShouldBeRejected() throws Exception {
		UploadSessionWireCodec otherCodec = new UploadSessionWireCodec(
			objectMapper,
			"anotherUploadSessionsSecretForWrongKeySimulationOnlyMustBeAtLeast256Bits!!"
		);
		String request = objectMapper.writeValueAsString(otherCodec.encodeCreateRequest(
			new CreateUploadSessionRequest("wrong-secret.zip", 11, 8, 2, ZIP_SHA256)
		));

		mockMvc.perform(post("/api/v1/upload-sessions")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_UPLOAD_SESSION_REQUEST"));
	}

	@Test
	void finalizeShouldFailWhenHashDoesNotMatch() throws Exception {
		UUID sessionId = createSession("bad.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		String encoded = encode(ZIP_BYTES);

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(1, encoded.substring(0, CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(2, encoded.substring(CHUNK_SIZE_BASE64_CHARS))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/finalize", sessionId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("sha256")));

		assertThat(boardPostRepository.count()).isZero();
		assertThat(uploadSessionRepository.count()).isEqualTo(1);
		var failedSession = uploadSessionRepository.findById(sessionId).orElseThrow();
		assertThat(failedSession.getStatus()).isEqualTo(UploadSessionStatus.FAILED);
		assertThat(failedSession.getUpdatedAt()).isAfter(failedSession.getCreatedAt());
	}

	@Test
	void uploadSessionEndpointsShouldRequireAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/upload-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(createSessionRequest("bundle.zip", 11, 8, 2, ZIP_SHA256)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void createSessionShouldRejectChunkSizeThatIsNotMultipleOf4() throws Exception {
		mockMvc.perform(post("/api/v1/upload-sessions")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createSessionRequest("bundle.zip", 11, 6, 2, ZIP_SHA256)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_UPLOAD_SESSION_REQUEST"));
	}

	@Test
	void uploadChunkShouldRejectInvalidBase64() throws Exception {
		UUID sessionId = createSession("bad.zip", ZIP_BYTES.length, CHUNK_SIZE_BASE64_CHARS, 2, ZIP_SHA256);

		mockMvc.perform(post("/api/v1/upload-sessions/{sessionId}/chunks", sessionId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(chunkRequest(1, "%%%bad%%%")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_UPLOAD_SESSION_REQUEST"));
	}

	private UUID createSession(String archiveName, int fileSizeBytes, int chunkSizeBase64Chars, int totalChunks, String sha256) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/upload-sessions")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createSessionRequest(archiveName, fileSizeBytes, chunkSizeBase64Chars, totalChunks, sha256)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.A6", notNullValue()))
			.andExpect(jsonPath("$.sessionId").doesNotExist())
			.andReturn();
		return readStatus(result).sessionId();
	}

	private UploadSessionStatusSnapshot readStatus(MvcResult result) throws Exception {
		UploadSessionStatusResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), UploadSessionStatusResponse.class);
		return uploadSessionWireCodec.decodeStatus(response);
	}

	private String tamperCiphertext(String value) {
		if (value.isEmpty()) {
			return value;
		}
		int index = Math.max(1, value.length() / 2);
		char targetChar = value.charAt(index);
		char replacement = switch (targetChar) {
			case 'A' -> 'B';
			case 'B' -> 'C';
			case 'C' -> 'D';
			case 'D' -> 'E';
			case 'E' -> 'F';
			case 'F' -> 'G';
			case 'G' -> 'H';
			case 'H' -> 'I';
			case 'I' -> 'J';
			case 'J' -> 'K';
			case 'K' -> 'L';
			case 'L' -> 'M';
			case 'M' -> 'N';
			case 'N' -> 'O';
			case 'O' -> 'P';
			case 'P' -> 'Q';
			case 'Q' -> 'R';
			case 'R' -> 'S';
			case 'S' -> 'T';
			case 'T' -> 'U';
			case 'U' -> 'V';
			case 'V' -> 'W';
			case 'W' -> 'X';
			case 'X' -> 'Y';
			case 'Y' -> 'Z';
			case 'Z' -> 'a';
			case 'a' -> 'b';
			case 'b' -> 'c';
			case 'c' -> 'd';
			case 'd' -> 'e';
			case 'e' -> 'f';
			case 'f' -> 'g';
			case 'g' -> 'h';
			case 'h' -> 'i';
			case 'i' -> 'j';
			case 'j' -> 'k';
			case 'k' -> 'l';
			case 'l' -> 'm';
			case 'm' -> 'n';
			case 'n' -> 'o';
			case 'o' -> 'p';
			case 'p' -> 'q';
			case 'q' -> 'r';
			case 'r' -> 's';
			case 's' -> 't';
			case 't' -> 'u';
			case 'u' -> 'v';
			case 'v' -> 'w';
			case 'w' -> 'x';
			case 'x' -> 'y';
			case 'y' -> 'z';
			case 'z' -> '0';
			case '0' -> '1';
			case '1' -> '2';
			case '2' -> '3';
			case '3' -> '4';
			case '4' -> '5';
			case '5' -> '6';
			case '6' -> '7';
			case '7' -> '8';
			case '8' -> '9';
			case '9' -> '-';
			case '-' -> '_';
			default -> 'A';
		};
		StringBuilder builder = new StringBuilder(value);
		builder.setCharAt(index, replacement);
		return builder.toString();
	}

	private String createSessionRequest(String archiveName, int fileSizeBytes, int chunkSizeBase64Chars, int totalChunks, String sha256) throws Exception {
		return objectMapper.writeValueAsString(uploadSessionWireCodec.encodeCreateRequest(
			new CreateUploadSessionRequest(archiveName, fileSizeBytes, chunkSizeBase64Chars, totalChunks, sha256)
		));
	}

	private String chunkRequest(int chunkNumber, String chunkDataBase64) throws Exception {
		return objectMapper.writeValueAsString(uploadSessionWireCodec.encodeChunkRequest(chunkNumber, chunkDataBase64));
	}

	private long extractId(String responseBody) {
		try {
			return objectMapper.readTree(responseBody).path("id").asLong();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to extract post id", exception);
		}
	}

	private String encode(byte[] bytes) {
		return java.util.Base64.getEncoder().encodeToString(bytes);
	}

	private byte[] slice(byte[] bytes, int startInclusive, int endExclusive) {
		byte[] slice = new byte[endExclusive - startInclusive];
		System.arraycopy(bytes, startInclusive, slice, 0, slice.length);
		return slice;
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
}
