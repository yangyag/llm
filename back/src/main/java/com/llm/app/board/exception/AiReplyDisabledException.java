package com.llm.app.board.exception;

/**
 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료.
 *             POST /api/v1/posts/{id}/ai-replies 는 410 Gone 비활성 스텁으로만 유지되며 신규 호출 금지.
 */
@Deprecated
public class AiReplyDisabledException extends RuntimeException {
	public AiReplyDisabledException() {
		super("AI replies are no longer supported");
	}
}
