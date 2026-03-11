package com.llm.app.common.web;

import java.time.Instant;

public record ErrorResponse(
	String code,
	String message,
	Instant timestamp,
	String path
) {
}
