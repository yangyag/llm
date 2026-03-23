package com.llm.app.board.dto;

import com.llm.app.board.model.BoardPostMode;
import java.time.Instant;
import java.util.List;

public record BoardPostDetailResponse(
	Long id,
	String title,
	String body,
	BoardPostMode mode,
	boolean conversionReady,
	Instant createdAt,
	Instant updatedAt,
	BoardAttachmentDto attachment,
	List<BoardReplyDto> replies
) {
}
