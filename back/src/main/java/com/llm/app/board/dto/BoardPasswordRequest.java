package com.llm.app.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BoardPasswordRequest(
	@NotBlank(message = "password is required")
	@Size(max = 100, message = "password must be 100 characters or less")
	String password
) {
}
