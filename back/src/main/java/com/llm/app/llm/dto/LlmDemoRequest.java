package com.llm.app.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LlmDemoRequest(
	@NotBlank(message = "prompt is required")
	@Size(max = 500, message = "prompt must be 500 characters or less")
	String prompt,
	@Size(max = 100, message = "model must be 100 characters or less")
	String model
) {
}
