package com.llm.app.board.repository;

import java.time.Instant;

public interface BoardPostSummaryProjection {
	Long getId();

	String getTitle();

	long getReplyCount();

	Instant getCreatedAt();
}
