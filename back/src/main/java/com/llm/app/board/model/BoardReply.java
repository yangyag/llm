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

	/** 작성자 username. AI 답변은 null이며 일반 댓글은 항상 기록된다. */
	@Column(name = "author_username", length = 100)
	private String authorUsername;

	@Column(name = "author_user_id")
	private Long authorUserId;

	/**
	 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료.
	 *             레거시 AI 답변 행 조회/보호용으로만 유지되며 신규 생성 금지. 컬럼 제거 없음.
	 */
	@Column(name = "is_ai", nullable = false)
	private boolean ai;

	/**
	 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료.
	 *             레거시 AI 답변 행 조회/보호용으로만 유지되며 신규 생성 금지. 컬럼 제거 없음.
	 */
	@Column(name = "ai_provider", length = 32)
	private String aiProvider;

	/**
	 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료.
	 *             레거시 AI 답변 행 조회/보호용으로만 유지되며 신규 생성 금지. 컬럼 제거 없음.
	 */
	@Column(name = "ai_model", length = 64)
	private String aiModel;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BoardReply() {
	}

	public BoardReply(BoardPost post, String body, String authorUsername, Instant createdAt, Instant updatedAt) {
		this(post, body, authorUsername, createdAt, updatedAt, false, null, null);
	}

	public BoardReply(
		BoardPost post,
		String body,
		String authorUsername,
		Instant createdAt,
		Instant updatedAt,
		boolean ai,
		String aiProvider,
		String aiModel
	) {
		this.post = post;
		this.body = body;
		this.authorUsername = authorUsername;
		this.ai = ai;
		this.aiProvider = aiProvider;
		this.aiModel = aiModel;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public BoardReply(BoardPost post, String body, String authorUsername, Instant createdAt,
		Instant updatedAt, Long authorUserId) {
		this(post, body, authorUsername, createdAt, updatedAt);
		this.authorUserId = authorUserId;
	}

	public Long getAuthorUserId() {
		return authorUserId;
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

	public String getAuthorUsername() {
		return authorUsername;
	}

	public boolean isAi() {
		return ai;
	}

	public String getAiProvider() {
		return aiProvider;
	}

	public String getAiModel() {
		return aiModel;
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
