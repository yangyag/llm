package com.llm.app.upload.exception;

import java.util.UUID;

public class UploadSessionNotFoundException extends RuntimeException {
    private UploadSessionNotFoundException(String message) {
        super(message);
    }

    public static UploadSessionNotFoundException session(UUID sessionId) {
        return new UploadSessionNotFoundException("upload session not found: " + sessionId);
    }
}
