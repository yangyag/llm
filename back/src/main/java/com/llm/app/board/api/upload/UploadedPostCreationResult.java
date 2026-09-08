package com.llm.app.board.api.upload;

import java.time.Instant;
import java.util.List;

public record UploadedPostCreationResult(
    Long id,
    String title,
    String body,
    String mode,
    boolean conversionReady,
    String authorUsername,
    Long authorUserId,
    Instant createdAt,
    Instant updatedAt,
    List<Attachment> attachments,
    List<Reply> replies
) {
    public UploadedPostCreationResult {
        attachments = List.copyOf(attachments);
        replies = List.copyOf(replies);
    }

    public record Attachment(
        Long id,
        String originalFilename,
        long size,
        String contentType
    ) {
    }

    public record Reply(
        Long id,
        String body,
        boolean ai,
        String aiProvider,
        String aiModel,
        String authorUsername,
        Long authorUserId,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
