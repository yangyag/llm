package com.llm.app.board.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "attachment_file_deletions")
public class AttachmentFileDeletion {
    @Id
    @Column(name = "storage_path", length = 1000)
    private String storagePath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AttachmentFileDeletion() { }

    public AttachmentFileDeletion(String storagePath) {
        this.storagePath = storagePath;
        this.createdAt = Instant.now();
    }

    public String getStoragePath() { return storagePath; }
}
