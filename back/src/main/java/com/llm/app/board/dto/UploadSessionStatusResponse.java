package com.llm.app.board.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadSessionStatusResponse(
	@JsonProperty("A6") String sessionId,
	@JsonProperty("A1") String archiveName,
	@JsonProperty("A2") String fileSizeBytes,
	@JsonProperty("A3") String chunkSizeBase64Chars,
	@JsonProperty("A4") String totalChunks,
	@JsonProperty("A7") String uploadedChunks,
	@JsonProperty("A8") String complete,
	@JsonProperty("A9") String expiresAt
) {
}
