package com.llm.app.board.dto;

import java.util.List;

public record BoardPostListResponse(
	List<BoardPostSummaryDto> items,
	int page,
	int pageSize,
	long totalItems,
	int totalPages,
	boolean hasPrevious,
	boolean hasNext
) {
}
