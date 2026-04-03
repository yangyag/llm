package com.llm.app.board.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record EncryptedUploadSessionChunkUploadRequest(
	@NotBlank(message = "A10 is required")
	@JsonProperty("A10") String a10,
	@NotBlank(message = "A11 is required")
	@JsonProperty("A11") String a11
) {
}
