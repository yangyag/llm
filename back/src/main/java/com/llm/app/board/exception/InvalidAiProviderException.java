package com.llm.app.board.exception;

public class InvalidAiProviderException extends RuntimeException {
	public InvalidAiProviderException(String provider) {
		super("Unsupported AI provider: " + provider);
	}
}
