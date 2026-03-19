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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
	name = "post_attachments",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_post_attachments_post_id", columnNames = "post_id")
	}
)
public class BoardAttachment {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false)
	private BoardPost post;

	@Column(name = "original_filename", nullable = false, length = 255)
	private String originalFilename;

	@Column(name = "stored_filename", nullable = false, length = 255)
	private String storedFilename;

	@Column(name = "storage_path", nullable = false, length = 1000)
	private String storagePath;

	@Column(name = "content_type", length = 255)
	private String contentType;

	@Column(nullable = false)
	private long size;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected BoardAttachment() {
	}

	public BoardAttachment(
		BoardPost post,
		String originalFilename,
		String storedFilename,
		String storagePath,
		String contentType,
		long size,
		Instant createdAt
	) {
		this.post = post;
		this.originalFilename = originalFilename;
		this.storedFilename = storedFilename;
		this.storagePath = storagePath;
		this.contentType = contentType;
		this.size = size;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public BoardPost getPost() {
		return post;
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

	public String getContentType() {
		return contentType;
	}

	public long getSize() {
		return size;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
