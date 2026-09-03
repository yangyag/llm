package com.llm.app.board.exception;

/**
 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 데이터 조회/보호용으로만 유지되며 신규 호출 금지.
 *             레거시 AI 답변 행의 수정/삭제 잠금(ensureReplyIsEditable)에서는 여전히 사용 중이므로 삭제 금지.
 */
@Deprecated
public class AiReplyModificationNotAllowedException extends RuntimeException {
	public AiReplyModificationNotAllowedException() {
		super("AI replies cannot be updated or deleted");
	}
}
