package com.llm.app.board.dto;

public record BoardAttachmentDto(
	Long id,
	String originalFilename,
	long size,
	String contentType,
	String downloadUrl
) {
}
