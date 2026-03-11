package com.llm.app.board.exception;

public class BoardPostNotFoundException extends RuntimeException {
	public BoardPostNotFoundException(Long id) {
		super("post not found: " + id);
	}
}
