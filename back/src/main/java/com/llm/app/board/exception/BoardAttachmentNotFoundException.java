package com.llm.app.board.exception;

public class BoardAttachmentNotFoundException extends RuntimeException {
	public BoardAttachmentNotFoundException(Long postId) {
		super("Attachment not found for post id=" + postId);
	}
}
