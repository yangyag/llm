package com.llm.app.board.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateBoardReplyRequest(
	@NotBlank(message = "bodyBase64 is required")
	String bodyBase64
) {
}
