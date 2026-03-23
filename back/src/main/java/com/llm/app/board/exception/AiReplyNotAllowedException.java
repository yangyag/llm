package com.llm.app.board.exception;

public class AiReplyNotAllowedException extends RuntimeException {
	public AiReplyNotAllowedException() {
		super("AI replies are not allowed for file conversion request posts");
	}
}
