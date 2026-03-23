package com.llm.app.board.dto;

import com.llm.app.board.model.BoardPostMode;
import java.time.Instant;

public record BoardPostSummaryDto(
	Long id,
	String title,
	BoardPostMode mode,
	boolean conversionReady,
	int replyCount,
	boolean hasAttachment,
	Instant createdAt
) {
}
