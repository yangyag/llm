package com.llm.app.board.service;

import com.llm.app.board.model.AttachmentFileDeletion;
import com.llm.app.board.repository.AttachmentFileDeletionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AttachmentFileLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AttachmentFileLifecycle.class);
    private final AttachmentFileDeletionRepository repository;
    private final AttachmentDeletionWorker worker;

    public AttachmentFileLifecycle(AttachmentFileDeletionRepository repository, AttachmentDeletionWorker worker) {
        this.repository = repository;
        this.worker = worker;
    }

    public void trackCreated(String path) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) worker.deleteAfterRollback(path);
                if (status == STATUS_UNKNOWN) log.error("Retaining attachment with unknown transaction outcome: {}", path);
            }
        });
    }

    public void deleteAfterCommit(String path) {
        repository.save(new AttachmentFileDeletion(path));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { worker.process(path); }
        });
    }
}
