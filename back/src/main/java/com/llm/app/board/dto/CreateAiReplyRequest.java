package com.llm.app.board.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAiReplyRequest(
	@NotBlank(message = "provider is required")
	String provider
) {
}
