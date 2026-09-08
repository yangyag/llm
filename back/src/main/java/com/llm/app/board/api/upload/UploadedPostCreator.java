package com.llm.app.board.api.upload;

public interface UploadedPostCreator {
    UploadedPostCreationResult create(UploadedPostCreationCommand command);
}
