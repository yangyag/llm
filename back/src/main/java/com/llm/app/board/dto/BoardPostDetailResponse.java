package com.llm.app.board.dto;

import java.time.Instant;
import java.util.List;

public record BoardPostDetailResponse(
	Long id,
	String title,
	String body,
	Instant createdAt,
	Instant updatedAt,
	List<BoardReplyDto> replies
) {
}
