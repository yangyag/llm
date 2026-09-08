package com.llm.app.upload.dto;

import java.time.Instant;
import java.util.List;

public record UploadedPostResponse(
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
    public UploadedPostResponse {
        attachments = List.copyOf(attachments);
        replies = List.copyOf(replies);
    }

    public record Attachment(
        Long id,
        String originalFilename,
        long size,
        String contentType,
        String downloadUrl
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
