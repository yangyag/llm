package com.llm.app.board.exception;

public class FileConversionLockedException extends RuntimeException {
	public FileConversionLockedException(Long postId) {
		super("File conversion request post is locked after conversion. post id=" + postId);
	}
}
