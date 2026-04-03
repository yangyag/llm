package com.llm.app.board.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "upload_sessions")
public class UploadSession {
	@Id
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "archive_name", nullable = false, length = 255)
	private String archiveName;

	@Column(name = "file_size_bytes", nullable = false)
	private long fileSizeBytes;

	@Column(name = "chunk_size_bytes", nullable = false)
	private long chunkSizeBase64Chars;

	@Column(name = "total_chunks", nullable = false)
	private int totalChunks;

	@Column(name = "file_sha256", nullable = false, length = 64)
	private String fileSha256;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private UploadSessionStatus status;

	@Column(name = "created_by", nullable = false, length = 100)
	private String createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected UploadSession() {
	}

	public UploadSession(
		UUID id,
		String archiveName,
		long fileSizeBytes,
		long chunkSizeBase64Chars,
		int totalChunks,
		String fileSha256,
		UploadSessionStatus status,
		String createdBy,
		Instant createdAt,
		Instant updatedAt,
		Instant expiresAt
	) {
		this.id = id;
		this.archiveName = archiveName;
		this.fileSizeBytes = fileSizeBytes;
		this.chunkSizeBase64Chars = chunkSizeBase64Chars;
		this.totalChunks = totalChunks;
		this.fileSha256 = normalizeSha256(fileSha256);
		this.status = status;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.expiresAt = expiresAt;
	}

	public UUID getId() {
		return id;
	}

	public String getArchiveName() {
		return archiveName;
	}

	public long getFileSizeBytes() {
		return fileSizeBytes;
	}

	public long getChunkSizeBase64Chars() {
		return chunkSizeBase64Chars;
	}

	public int getTotalChunks() {
		return totalChunks;
	}

	public String getFileSha256() {
		return fileSha256;
	}

	public UploadSessionStatus getStatus() {
		return status;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void markFinalizing(Instant now) {
		this.status = UploadSessionStatus.FINALIZING;
		this.updatedAt = now;
	}

	public void markFailed(Instant now) {
		this.status = UploadSessionStatus.FAILED;
		this.updatedAt = now;
	}

	public void refreshExpiry(Instant now, long expirationMs) {
		this.updatedAt = now;
		this.expiresAt = now.plusMillis(expirationMs);
		if (status == UploadSessionStatus.FAILED) {
			this.status = UploadSessionStatus.PENDING;
		}
	}

	public void markCompleted(Instant now) {
		this.status = UploadSessionStatus.COMPLETED;
		this.updatedAt = now;
	}

	public boolean isExpired(Instant now) {
		return expiresAt.isBefore(now);
	}

	private String normalizeSha256(String value) {
		return value.toLowerCase(Locale.ROOT);
	}
}
