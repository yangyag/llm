package com.llm.app.upload.service;

import com.llm.app.board.api.upload.UploadedPostCreationResult;
import com.llm.app.upload.dto.UploadedPostResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UploadedPostResultMapper {
    public UploadedPostResponse toResponse(UploadedPostCreationResult result) {
        List<UploadedPostResponse.Attachment> attachments = result.attachments().stream()
            .map(attachment -> new UploadedPostResponse.Attachment(
                attachment.id(),
                attachment.originalFilename(),
                attachment.size(),
                attachment.contentType(),
                "/api/v1/posts/" + result.id() + "/attachments/" + attachment.id()
            ))
            .toList();
        List<UploadedPostResponse.Reply> replies = result.replies().stream()
            .map(reply -> new UploadedPostResponse.Reply(
                reply.id(),
                reply.body(),
                reply.ai(),
                reply.aiProvider(),
                reply.aiModel(),
                reply.authorUsername(),
                reply.authorUserId(),
                reply.createdAt(),
                reply.updatedAt()
            ))
            .toList();

        return new UploadedPostResponse(
            result.id(),
            result.title(),
            result.body(),
            result.mode(),
            result.conversionReady(),
            result.authorUsername(),
            result.authorUserId(),
            result.createdAt(),
            result.updatedAt(),
            attachments,
            replies
        );
    }
}
