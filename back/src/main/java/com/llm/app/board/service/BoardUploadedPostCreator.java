package com.llm.app.board.service;

import com.llm.app.board.api.upload.UploadedPostCreationCommand;
import com.llm.app.board.api.upload.UploadedPostCreationResult;
import com.llm.app.board.api.upload.UploadedPostCreator;
import com.llm.app.board.model.BoardAttachment;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardPostMode;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BoardUploadedPostCreator implements UploadedPostCreator {
    private final BoardPostRepository boardPostRepository;
    private final BoardAttachmentRepository boardAttachmentRepository;
    private final AttachmentStorageService attachmentStorageService;
    private final AttachmentFileLifecycle attachmentFileLifecycle;

    public BoardUploadedPostCreator(
        BoardPostRepository boardPostRepository,
        BoardAttachmentRepository boardAttachmentRepository,
        AttachmentStorageService attachmentStorageService,
        AttachmentFileLifecycle attachmentFileLifecycle
    ) {
        this.boardPostRepository = boardPostRepository;
        this.boardAttachmentRepository = boardAttachmentRepository;
        this.attachmentStorageService = attachmentStorageService;
        this.attachmentFileLifecycle = attachmentFileLifecycle;
    }

    @Override
    public UploadedPostCreationResult create(UploadedPostCreationCommand command) {
        Instant timestamp = command.createdAt();
        BoardPost post = boardPostRepository.save(new BoardPost(
            command.title(),
            command.body(),
            BoardPostMode.FILE_CONVERSION_REQUEST,
            command.authorUsername(),
            timestamp,
            timestamp,
            command.authorUserId()
        ));

        AttachmentStorageService.StoredAttachment storedAttachment = attachmentStorageService.store(
            command.assembledPath(),
            command.originalFilename(),
            command.contentType()
        );
        attachmentFileLifecycle.trackCreated(storedAttachment.storagePath());

        BoardAttachment attachment = boardAttachmentRepository.save(new BoardAttachment(
            post,
            storedAttachment.originalFilename(),
            storedAttachment.storedFilename(),
            storedAttachment.storagePath(),
            storedAttachment.contentType(),
            storedAttachment.size(),
            timestamp
        ));

        return new UploadedPostCreationResult(
            post.getId(),
            post.getTitle(),
            post.getBody(),
            post.getMode().name(),
            true,
            post.getAuthorUsername(),
            post.getAuthorUserId(),
            post.getCreatedAt(),
            post.getUpdatedAt(),
            List.of(new UploadedPostCreationResult.Attachment(
                attachment.getId(),
                attachment.getOriginalFilename(),
                attachment.getSize(),
                attachment.getContentType()
            )),
            List.of()
        );
    }
}
