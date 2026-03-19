package com.llm.app.board.exception;

public class AttachmentTooLargeException extends RuntimeException {
	public AttachmentTooLargeException(long maxSizeBytes) {
		super("Attachment must be " + maxSizeBytes + " bytes or less");
	}
}
