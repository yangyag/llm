package com.llm.app.upload.exception;

public class UploadSessionChunkTooLargeException extends RuntimeException {
    public UploadSessionChunkTooLargeException(long maxSizeBytes) {
        super("Attachment must be " + maxSizeBytes + " bytes or less");
    }
}
