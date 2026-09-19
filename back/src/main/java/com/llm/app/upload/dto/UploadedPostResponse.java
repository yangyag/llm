package com.llm.app.upload.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadedPostResponse(
    Long id,
    String title,
    String body,
    String bodyFormat,
    JsonNode bodyDocument,
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
        String attachmentKind,
        UUID inlineKey,
        String downloadUrl,
        String contentUrl
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
