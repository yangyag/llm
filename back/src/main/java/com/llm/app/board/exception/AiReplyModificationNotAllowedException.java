package com.llm.app.board.exception;

public class AiReplyModificationNotAllowedException extends RuntimeException {
	public AiReplyModificationNotAllowedException() {
		super("AI replies cannot be updated or deleted");
	}
}
