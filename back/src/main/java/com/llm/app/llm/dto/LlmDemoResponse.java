package com.llm.app.llm.dto;

import java.time.Instant;

public record LlmDemoResponse(
	String prompt,
	String response,
	String model,
	Instant timestamp
) {
}
