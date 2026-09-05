package com.llm.app.board.dto;

import java.time.Instant;

/**
 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료.
 *             ai/aiProvider/aiModel 필드는 레거시 AI 답변 행 조회용으로만 유지되며 신규 생성 금지.
 */
@Deprecated
public record BoardReplyDto(
	Long id,
	String body,
	boolean ai,
	String aiProvider,
	String aiModel,
	String authorUsername,
	Long authorUserId,
	Instant createdAt,
	Instant updatedAt
) {
}
