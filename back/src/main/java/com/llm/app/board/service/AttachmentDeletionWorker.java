package com.llm.app.board.service;

import com.llm.app.board.model.AttachmentFileDeletion;
import com.llm.app.board.repository.AttachmentFileDeletionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AttachmentDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(AttachmentDeletionWorker.class);
    private final AttachmentFileDeletionRepository repository;
    private final AttachmentStorageService storage;
    private final TransactionTemplate transaction;

    public AttachmentDeletionWorker(AttachmentFileDeletionRepository repository,
        AttachmentStorageService storage, PlatformTransactionManager manager) {
        this.repository = repository;
        this.storage = storage;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void deleteAfterRollback(String path) {
        try {
            storage.deleteIfExists(path);
        } catch (RuntimeException failure) {
            try {
                transaction.executeWithoutResult(status -> repository.save(new AttachmentFileDeletion(path)));
            } catch (RuntimeException queueFailure) {
                log.error("Unable to queue rolled-back attachment cleanup: {}", path, queueFailure);
            }
        }
    }

    public void process(String path) {
        try {
            transaction.executeWithoutResult(status -> repository.findById(path).ifPresent(task -> {
                storage.deleteIfExists(path);
                repository.delete(task);
            }));
        } catch (RuntimeException failure) {
            // Keep the committed task for retry. Cleanup failure must not turn a committed write into 500.
            log.warn("Attachment deletion will be retried: {}", path, failure);
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void retryPending() {
        for (AttachmentFileDeletion task : repository.findTop100ByOrderByCreatedAtAsc()) {
            process(task.getStoragePath());
        }
    }
}
