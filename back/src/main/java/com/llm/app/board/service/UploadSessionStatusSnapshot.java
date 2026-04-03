package com.llm.app.board.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadSessionStatusSnapshot(
	UUID sessionId,
	String archiveName,
	long fileSizeBytes,
	long chunkSizeBase64Chars,
	int totalChunks,
	List<Integer> uploadedChunks,
	boolean complete,
	Instant expiresAt
) {
}
