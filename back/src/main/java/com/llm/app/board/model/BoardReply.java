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
@Table(name = "post_replies")
public class BoardReply {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false)
	private BoardPost post;

	@Column(nullable = false, columnDefinition = "text")
	private String body;

	@Column(name = "is_ai", nullable = false)
	private boolean ai;

	@Column(name = "ai_provider", length = 32)
	private String aiProvider;

	@Column(name = "password_hash", nullable = false, length = 120)
	private String passwordHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BoardReply() {
	}

	public BoardReply(BoardPost post, String body, String passwordHash, Instant createdAt, Instant updatedAt) {
		this(post, body, passwordHash, createdAt, updatedAt, false, null);
	}

	public BoardReply(
		BoardPost post,
		String body,
		String passwordHash,
		Instant createdAt,
		Instant updatedAt,
		boolean ai,
		String aiProvider
	) {
		this.post = post;
		this.body = body;
		this.ai = ai;
		this.aiProvider = aiProvider;
		this.passwordHash = passwordHash;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public Long getId() {
		return id;
	}

	public BoardPost getPost() {
		return post;
	}

	public String getBody() {
		return body;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public boolean isAi() {
		return ai;
	}

	public String getAiProvider() {
		return aiProvider;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void update(String body, Instant updatedAt) {
		this.body = body;
		this.updatedAt = updatedAt;
	}
}
