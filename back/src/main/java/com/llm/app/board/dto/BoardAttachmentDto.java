package com.llm.app.board.dto;

import com.llm.app.board.model.BoardAttachmentKind;
import java.util.UUID;

public record BoardAttachmentDto(
	Long id,
	String originalFilename,
	long size,
	String contentType,
	BoardAttachmentKind attachmentKind,
	UUID inlineKey,
	String downloadUrl,
	String contentUrl
) {
}
