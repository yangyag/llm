package com.llm.app.board.repository;

import com.llm.app.board.model.BoardPostMode;
import java.time.Instant;

public interface BoardPostSummaryProjection {
	Long getId();

	String getTitle();

	BoardPostMode getMode();

	boolean getConversionReady();

	long getReplyCount();

	boolean getHasAttachment();

	Instant getCreatedAt();
}
