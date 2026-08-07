package com.llm.app.board.dto;

import java.time.Instant;

public record BoardReplyDto(
	Long id,
	String body,
	boolean ai,
	String aiProvider,
	String aiModel,
	String authorUsername,
	Instant createdAt,
	Instant updatedAt
) {
}
