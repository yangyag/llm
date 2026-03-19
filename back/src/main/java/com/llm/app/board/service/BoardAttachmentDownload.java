package com.llm.app.board.service;

import org.springframework.core.io.Resource;

public record BoardAttachmentDownload(
	Resource resource,
	String originalFilename,
	String contentType,
	long size
) {
}
