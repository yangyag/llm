package com.llm.app.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateBoardPostRequest(
	@NotBlank(message = "title is required")
	@Size(max = 200, message = "title must be 200 characters or less")
	String title,
	@NotBlank(message = "bodyBase64 is required")
	String bodyBase64,
	@NotBlank(message = "password is required")
	@Size(max = 100, message = "password must be 100 characters or less")
	String password
) {
}
