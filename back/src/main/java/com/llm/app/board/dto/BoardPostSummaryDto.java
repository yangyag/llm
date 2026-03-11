package com.llm.app.board.dto;

import java.time.Instant;

public record BoardPostSummaryDto(
	Long id,
	String title,
	int replyCount,
	Instant createdAt
) {
}
