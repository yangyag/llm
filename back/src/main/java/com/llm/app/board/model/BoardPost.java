package com.llm.app.board.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "posts")
public class BoardPost {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(name = "body_format", nullable = false, length = 30)
	private PostBodyFormat bodyFormat = PostBodyFormat.PLAIN_TEXT;

	@Column(name = "body_document", columnDefinition = "text")
	private String bodyDocument;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private BoardPostMode mode;

	/** 작성자 username. 레거시 글은 null일 수 있으며 그 경우 관리자만 수정/삭제 가능. */
	@Column(name = "author_username", length = 100)
	private String authorUsername;

	@Column(name = "author_user_id")
	private Long authorUserId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("createdAt ASC")
	private List<BoardReply> replies = new ArrayList<>();

	protected BoardPost() {
	}

	public BoardPost(
		String title,
		String body,
		BoardPostMode mode,
		String authorUsername,
		Instant createdAt,
		Instant updatedAt
	) {
		this.title = title;
		this.body = body;
		this.mode = mode;
		this.authorUsername = authorUsername;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public BoardPost(String title, String body, BoardPostMode mode, String authorUsername,
		Instant createdAt, Instant updatedAt, Long authorUserId) {
		this(title, body, mode, authorUsername, createdAt, updatedAt);
		this.authorUserId = authorUserId;
	}

	public BoardPost(String title, String body, BoardPostMode mode, String authorUsername,
		Instant createdAt, Instant updatedAt, Long authorUserId,
		PostBodyFormat bodyFormat, String bodyDocument) {
		this(title, body, mode, authorUsername, createdAt, updatedAt, authorUserId);
		setBody(body, bodyFormat, bodyDocument);
	}

	public Long getAuthorUserId() {
		return authorUserId;
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public PostBodyFormat getBodyFormat() {
		return bodyFormat;
	}

	public String getBodyDocument() {
		return bodyDocument;
	}

	public BoardPostMode getMode() {
		return mode;
	}

	public String getAuthorUsername() {
		return authorUsername;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public List<BoardReply> getReplies() {
		return replies;
	}

	public void update(String title, String body, BoardPostMode mode, Instant updatedAt) {
		update(title, body, PostBodyFormat.PLAIN_TEXT, null, mode, updatedAt);
	}

	public void update(String title, String body, PostBodyFormat bodyFormat, String bodyDocument,
		BoardPostMode mode, Instant updatedAt) {
		setBody(body, bodyFormat, bodyDocument);
		this.title = title;
		this.mode = mode;
		this.updatedAt = updatedAt;
	}

	private void setBody(String body, PostBodyFormat bodyFormat, String bodyDocument) {
		if (body == null || bodyFormat == null) {
			throw new IllegalArgumentException("body and bodyFormat must not be null");
		}
		boolean valid = (bodyFormat == PostBodyFormat.PLAIN_TEXT && bodyDocument == null)
			|| (bodyFormat == PostBodyFormat.TIPTAP_JSON && bodyDocument != null);
		if (!valid) {
			throw new IllegalArgumentException("body document does not match the body format");
		}
		this.body = body;
		this.bodyFormat = bodyFormat;
		this.bodyDocument = bodyDocument;
	}
}
