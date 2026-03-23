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
	@Column(nullable = false, length = 40)
	private BoardPostMode mode;

	@Column(name = "password_hash", nullable = false, length = 120)
	private String passwordHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("createdAt ASC")
	private List<BoardReply> replies = new ArrayList<>();

	protected BoardPost() {
	}

	public BoardPost(String title, String body, BoardPostMode mode, String passwordHash, Instant createdAt, Instant updatedAt) {
		this.title = title;
		this.body = body;
		this.mode = mode;
		this.passwordHash = passwordHash;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
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

	public BoardPostMode getMode() {
		return mode;
	}

	public String getPasswordHash() {
		return passwordHash;
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
		this.title = title;
		this.body = body;
		this.mode = mode;
		this.updatedAt = updatedAt;
	}
}
