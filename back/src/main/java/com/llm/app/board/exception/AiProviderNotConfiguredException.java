package com.llm.app.board.exception;

public class AiProviderNotConfiguredException extends RuntimeException {
	public AiProviderNotConfiguredException(String provider) {
		super(provider + " provider is not configured");
	}
}
