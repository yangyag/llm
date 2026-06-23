package com.llm.app.board.exception;

import java.util.UUID;

public class NotFoundException extends RuntimeException {
    private NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException post(Long id) {
        return new NotFoundException("post not found: " + id);
    }

    public static NotFoundException reply(Long id) {
        return new NotFoundException("reply not found: " + id);
    }

    public static NotFoundException attachment(Long postId) {
        return new NotFoundException("Attachment not found for post id=" + postId);
    }

    public static NotFoundException uploadSession(UUID sessionId) {
        return new NotFoundException("upload session not found: " + sessionId);
    }
}
