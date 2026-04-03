package com.llm.app.board.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record EncryptedUploadSessionCreateRequest(
	@NotBlank(message = "A1 is required")
	@JsonProperty("A1") String a1,
	@NotBlank(message = "A2 is required")
	@JsonProperty("A2") String a2,
	@NotBlank(message = "A3 is required")
	@JsonProperty("A3") String a3,
	@NotBlank(message = "A4 is required")
	@JsonProperty("A4") String a4,
	@NotBlank(message = "A5 is required")
	@JsonProperty("A5") String a5
) {
}
