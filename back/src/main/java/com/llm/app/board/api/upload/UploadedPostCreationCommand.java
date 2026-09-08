package com.llm.app.board.api.upload;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record UploadedPostCreationCommand(
    String title,
    String body,
    Path assembledPath,
    String originalFilename,
    String contentType,
    Long authorUserId,
    String authorUsername,
    Instant createdAt
) {
    public UploadedPostCreationCommand {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(assembledPath, "assembledPath");
        Objects.requireNonNull(originalFilename, "originalFilename");
        Objects.requireNonNull(authorUserId, "authorUserId");
        Objects.requireNonNull(authorUsername, "authorUsername");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
