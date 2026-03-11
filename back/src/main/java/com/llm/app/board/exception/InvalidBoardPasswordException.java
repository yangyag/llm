package com.llm.app.board.exception;

public class InvalidBoardPasswordException extends RuntimeException {
	public InvalidBoardPasswordException() {
		super("password does not match");
	}
}
