package com.llm.app.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.upload.dto.EncryptedUploadSessionChunkUploadRequest;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.unit.DataSize;

class UploadSessionChunkWireLimitTest {
	private static final int MAX_JSON_STRING_LENGTH = StreamReadConstraints.defaults().getMaxStringLength();
	private static final long MAX_TRANSMITTABLE_CHUNK = 11_249_976L;

	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
	private final UploadSessionWireCodec codec = new UploadSessionWireCodec(
		objectMapper,
		"chunkWireLimitTestSecretMustBeLongEnoughForKeyDerivation!!"
	);

	@TempDir
	Path tempDir;

	@Test
	void transmittableChunkSizeFollowsJacksonStringLimit() {
		assertThat(MAX_JSON_STRING_LENGTH).isEqualTo(20_000_000);
		assertThat(UploadSessionStorageService.maxTransmittableDecodedChunkSize(MAX_JSON_STRING_LENGTH))
			.isEqualTo(MAX_TRANSMITTABLE_CHUNK);
	}

	@Test
	void largestTransmittableChunkParsesButNextChunkSizeIsRejectedByJackson() throws Exception {
		String accepted = encryptedChunkJson((int) MAX_TRANSMITTABLE_CHUNK);
		EncryptedUploadSessionChunkUploadRequest parsed =
			objectMapper.readValue(accepted, EncryptedUploadSessionChunkUploadRequest.class);
		assertThat(codec.decodeChunkRequest(parsed).chunkDataBase64())
			.hasSize((int) (MAX_TRANSMITTABLE_CHUNK / 3 * 4));

		// 다음으로 큰 청크(Base64 4자 = 3바이트 증가)는 A11 필드가 문자열 길이 한도를 넘는다.
		String rejected = encryptedChunkJson((int) MAX_TRANSMITTABLE_CHUNK + 3);
		assertThatThrownBy(() -> objectMapper.readValue(rejected, EncryptedUploadSessionChunkUploadRequest.class))
			.isInstanceOf(JsonProcessingException.class)
			.hasMessageContaining("exceeds the maximum allowed");
	}

	@Test
	void configuredChunkSizeAboveWireLimitIsCapped() {
		UploadSessionStorageService capped = new UploadSessionStorageService(tempDir.toString(), DataSize.ofMegabytes(100));
		assertThat(capped.getMaxDecodedChunkSizeBytes()).isEqualTo(MAX_TRANSMITTABLE_CHUNK);

		UploadSessionStorageService kept = new UploadSessionStorageService(tempDir.toString(), DataSize.ofMegabytes(8));
		assertThat(kept.getMaxDecodedChunkSizeBytes()).isEqualTo(DataSize.ofMegabytes(8).toBytes());
	}

	private String encryptedChunkJson(int decodedSize) throws Exception {
		String chunkBase64 = Base64.getEncoder().encodeToString(new byte[decodedSize]);
		return objectMapper.writeValueAsString(codec.encodeChunkRequest(1, chunkBase64));
	}
}
