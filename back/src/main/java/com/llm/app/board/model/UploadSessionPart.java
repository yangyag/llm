package com.llm.app.board.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "upload_session_parts")
public class UploadSessionPart {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private UploadSession session;

	@Column(name = "chunk_number", nullable = false)
	private int chunkNumber;

	@Column(name = "original_filename", nullable = false, length = 255)
	private String originalFilename;

	@Column(name = "stored_filename", nullable = false, length = 255)
	private String storedFilename;

	@Column(name = "storage_path", nullable = false, length = 1000)
	private String storagePath;

	@Column(nullable = false)
	private long size;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected UploadSessionPart() {
	}

	public UploadSessionPart(
		UploadSession session,
		int chunkNumber,
		String originalFilename,
		String storedFilename,
		String storagePath,
		long size,
		Instant createdAt
	) {
		this.session = session;
		this.chunkNumber = chunkNumber;
		this.originalFilename = originalFilename;
		this.storedFilename = storedFilename;
		this.storagePath = storagePath;
		this.size = size;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public UploadSession getSession() {
		return session;
	}

	public int getChunkNumber() {
		return chunkNumber;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public String getStoredFilename() {
		return storedFilename;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public long getSize() {
		return size;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
