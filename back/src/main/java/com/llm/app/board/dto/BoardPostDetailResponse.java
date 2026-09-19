package com.llm.app.board.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.llm.app.board.model.BoardPostMode;
import com.llm.app.board.model.PostBodyFormat;
import java.time.Instant;
import java.util.List;

public record BoardPostDetailResponse(
	Long id,
	String title,
	String body,
	PostBodyFormat bodyFormat,
	JsonNode bodyDocument,
	BoardPostMode mode,
	boolean conversionReady,
	String authorUsername,
	Long authorUserId,
	Instant createdAt,
	Instant updatedAt,
	List<BoardAttachmentDto> attachments,
	List<BoardReplyDto> replies
) {
}
