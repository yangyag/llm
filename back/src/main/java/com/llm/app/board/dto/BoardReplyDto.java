package com.llm.app.board.dto;

import java.time.Instant;

public record BoardReplyDto(
	Long id,
	String body,
	boolean ai,
	String aiProvider,
	Instant createdAt,
	Instant updatedAt
) {
}
