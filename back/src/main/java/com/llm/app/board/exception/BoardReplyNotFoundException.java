package com.llm.app.board.exception;

public class BoardReplyNotFoundException extends RuntimeException {
	public BoardReplyNotFoundException(Long id) {
		super("reply not found: " + id);
	}
}
