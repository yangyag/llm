package com.llm.app.board.service;

import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.BoardPostListResponse;
import com.llm.app.board.dto.BoardPostSummaryDto;
import com.llm.app.board.dto.BoardReplyDto;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardReply;
import com.llm.app.board.repository.BoardPostSummaryProjection;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class BoardMapper {
	public BoardPostListResponse toListResponse(Page<BoardPostSummaryProjection> posts) {
		return new BoardPostListResponse(
			posts.stream()
				.map(post -> new BoardPostSummaryDto(
					post.getId(),
					post.getTitle(),
					Math.toIntExact(post.getReplyCount()),
					post.getCreatedAt()
				))
				.toList(),
			posts.getNumber() + 1,
			posts.getSize(),
			posts.getTotalElements(),
			posts.getTotalPages(),
			posts.hasPrevious(),
			posts.hasNext()
		);
	}

	public BoardPostDetailResponse toDetailResponse(BoardPost post) {
		List<BoardReplyDto> replies = post.getReplies().stream()
			.map(this::toReplyDto)
			.toList();

		return new BoardPostDetailResponse(
			post.getId(),
			post.getTitle(),
			post.getBody(),
			post.getCreatedAt(),
			post.getUpdatedAt(),
			replies
		);
	}

	private BoardReplyDto toReplyDto(BoardReply reply) {
		return new BoardReplyDto(
			reply.getId(),
			reply.getBody(),
			reply.isAi(),
			reply.getAiProvider(),
			reply.getCreatedAt(),
			reply.getUpdatedAt()
		);
	}
}
