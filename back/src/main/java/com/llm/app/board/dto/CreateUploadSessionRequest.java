package com.llm.app.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadSessionRequest(
	@NotBlank(message = "archiveName is required")
	@Size(max = 255, message = "archiveName must be 255 characters or less")
	String archiveName,
	@Positive(message = "fileSizeBytes must be greater than 0")
	long fileSizeBytes,
	@Positive(message = "chunkSizeBase64Chars must be greater than 0")
	long chunkSizeBase64Chars,
	@Positive(message = "totalChunks must be greater than 0")
	int totalChunks,
	@NotBlank(message = "fileSha256 is required")
	@Pattern(regexp = "^[A-Fa-f0-9]{64}$", message = "fileSha256 must be a 64-character hex string")
	String fileSha256
) {
}
