package com.llm.app.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.board.dto.CreateUploadSessionRequest;
import com.llm.app.board.dto.EncryptedUploadSessionChunkUploadRequest;
import com.llm.app.board.dto.EncryptedUploadSessionCreateRequest;
import com.llm.app.board.dto.UploadSessionStatusResponse;
import com.llm.app.board.exception.InvalidUploadSessionRequestException;
import com.llm.app.board.service.UploadSessionWireCodec.ChunkUploadCommand;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UploadSessionWireCodecTest {

	private static final String SECRET = "testUploadSessionsSecretForUnitTestsOnlyMustBeAtLeast256BitsLong!!";

	private UploadSessionWireCodec codec;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper().findAndRegisterModules();
		codec = new UploadSessionWireCodec(objectMapper, SECRET);
	}

	@Test
	void createRequestShouldRoundTrip() {
		CreateUploadSessionRequest request = new CreateUploadSessionRequest(
			"bundle.zip", 12345L, 1398104L, 2, "abc123"
		);
		EncryptedUploadSessionCreateRequest encrypted = codec.encodeCreateRequest(request);
		assertThat(encrypted.a1()).isNotBlank();
		assertThat(encrypted.a2()).isNotBlank();
		CreateUploadSessionRequest decoded = codec.decodeCreateRequest(encrypted);
		assertThat(decoded.archiveName()).isEqualTo("bundle.zip");
		assertThat(decoded.fileSizeBytes()).isEqualTo(12345L);
		assertThat(decoded.chunkSizeBase64Chars()).isEqualTo(1398104L);
		assertThat(decoded.totalChunks()).isEqualTo(2);
		assertThat(decoded.fileSha256()).isEqualTo("abc123");
	}

	@Test
	void chunkRequestShouldRoundTrip() {
		ChunkUploadCommand command = codec.decodeChunkRequest(
			codec.encodeChunkRequest(3, "aGVsbG8=")
		);
		assertThat(command.chunkNumber()).isEqualTo(3);
		assertThat(command.chunkDataBase64()).isEqualTo("aGVsbG8=");
	}

	@Test
	void statusShouldRoundTrip() {
		UploadSessionStatusSnapshot snapshot = new UploadSessionStatusSnapshot(
			UUID.randomUUID(),
			"bundle.zip",
			12345L,
			1398104L,
			2,
			java.util.List.of(1, 2),
			true,
			java.time.Instant.parse("2026-12-31T00:00:00Z")
		);
		UploadSessionStatusResponse response = codec.encodeStatus(snapshot);
		UploadSessionStatusSnapshot decoded = codec.decodeStatus(response);
		assertThat(decoded.archiveName()).isEqualTo("bundle.zip");
		assertThat(decoded.fileSizeBytes()).isEqualTo(12345L);
		assertThat(decoded.totalChunks()).isEqualTo(2);
		assertThat(decoded.uploadedChunks()).containsExactly(1, 2);
		assertThat(decoded.complete()).isTrue();
	}

	@Test
	void ciphertextMovedToDifferentAliasShouldFailAad() throws Exception {
		EncryptedUploadSessionCreateRequest encrypted = codec.encodeCreateRequest(
			new CreateUploadSessionRequest("bundle.zip", 12345L, 1398104L, 2, "abc123")
		);
		// A1(archiveName) 값을 A2 자리로 옮기면 AAD 불일치로 복호화 실패해야 한다.
		com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
		node.put(UploadSessionWireCodec.A1, encrypted.a1());
		node.put(UploadSessionWireCodec.A2, encrypted.a1());
		node.put(UploadSessionWireCodec.A3, encrypted.a3());
		node.put(UploadSessionWireCodec.A4, encrypted.a4());
		node.put(UploadSessionWireCodec.A5, encrypted.a5());
		EncryptedUploadSessionCreateRequest swapped = objectMapper.readValue(
			objectMapper.writeValueAsString(node),
			EncryptedUploadSessionCreateRequest.class
		);
		assertThatThrownBy(() -> codec.decodeCreateRequest(swapped))
			.isInstanceOf(InvalidUploadSessionRequestException.class);
	}

	@Test
	void tamperedCiphertextShouldFailDecryption() {
		EncryptedUploadSessionCreateRequest encrypted = codec.encodeCreateRequest(
			new CreateUploadSessionRequest("bundle.zip", 12345L, 1398104L, 2, "abc123")
		);
		String tampered = encrypted.a1().substring(0, encrypted.a1().length() - 2) + "zz";
		EncryptedUploadSessionCreateRequest broken = new EncryptedUploadSessionCreateRequest(
			tampered,
			encrypted.a2(),
			encrypted.a3(),
			encrypted.a4(),
			encrypted.a5()
		);
		assertThatThrownBy(() -> codec.decodeCreateRequest(broken))
			.isInstanceOf(InvalidUploadSessionRequestException.class);
	}

	@Test
	void differentSecretShouldFailDecryption() {
		UploadSessionWireCodec other = new UploadSessionWireCodec(
			objectMapper,
			"anotherUploadSessionsSecretForWrongKeySimulationMustBeAtLeast256Bits!!"
		);
		EncryptedUploadSessionCreateRequest encrypted = other.encodeCreateRequest(
			new CreateUploadSessionRequest("bundle.zip", 12345L, 1398104L, 2, "abc123")
		);
		assertThatThrownBy(() -> codec.decodeCreateRequest(encrypted))
			.isInstanceOf(InvalidUploadSessionRequestException.class);
	}

	@Test
	void chunkRequestMovedToDifferentAliasShouldFailAad() throws Exception {
		EncryptedUploadSessionChunkUploadRequest chunk = codec.encodeChunkRequest(1, "aGVsbG8=");
		com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
		node.put(UploadSessionWireCodec.A10, chunk.a10());
		node.put(UploadSessionWireCodec.A11, chunk.a10());
		EncryptedUploadSessionChunkUploadRequest swapped = objectMapper.readValue(
			objectMapper.writeValueAsString(node),
			EncryptedUploadSessionChunkUploadRequest.class
		);
		assertThatThrownBy(() -> codec.decodeChunkRequest(swapped))
			.isInstanceOf(InvalidUploadSessionRequestException.class);
	}
}
