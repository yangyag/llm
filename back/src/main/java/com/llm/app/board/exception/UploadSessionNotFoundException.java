package com.llm.app.board.exception;

import java.util.UUID;

public class UploadSessionNotFoundException extends RuntimeException {
	public UploadSessionNotFoundException(UUID sessionId) {
		super("upload session not found: " + sessionId);
	}
}
