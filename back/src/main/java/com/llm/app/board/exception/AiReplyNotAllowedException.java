package com.llm.app.board.exception;

/**
 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 데이터 조회/보호용으로만 유지되며 신규 호출 금지.
 */
@Deprecated
public class AiReplyNotAllowedException extends RuntimeException {
	public AiReplyNotAllowedException() {
		super("AI replies are not allowed for file conversion request posts");
	}
}
