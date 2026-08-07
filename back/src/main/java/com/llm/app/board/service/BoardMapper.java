package com.llm.app.board.service;

import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.BoardPostListResponse;
import com.llm.app.board.dto.BoardPostSummaryDto;
import com.llm.app.board.dto.BoardReplyDto;
import com.llm.app.board.dto.BoardAttachmentDto;
import com.llm.app.board.model.BoardAttachment;
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
					post.getMode(),
					post.getConversionReady(),
					Math.toIntExact(post.getReplyCount()),
					post.getHasAttachment(),
					post.getAuthorUsername(),
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

	public BoardPostDetailResponse toDetailResponse(BoardPost post, List<BoardAttachment> attachments) {
		List<BoardReplyDto> replies = post.getReplies().stream()
			.map(this::toReplyDto)
			.toList();

		List<BoardAttachment> safeAttachments = attachments == null ? List.of() : attachments;
		List<BoardAttachmentDto> attachmentDtos = safeAttachments.stream()
			.map(attachment -> toAttachmentDto(post.getId(), attachment))
			.toList();

		return new BoardPostDetailResponse(
			post.getId(),
			post.getTitle(),
			post.getBody(),
			post.getMode(),
			post.getMode() == com.llm.app.board.model.BoardPostMode.FILE_CONVERSION_REQUEST && !safeAttachments.isEmpty(),
			post.getAuthorUsername(),
			post.getCreatedAt(),
			post.getUpdatedAt(),
			attachmentDtos,
			replies
		);
	}

	private BoardAttachmentDto toAttachmentDto(Long postId, BoardAttachment attachment) {
		return new BoardAttachmentDto(
			attachment.getId(),
			attachment.getOriginalFilename(),
			attachment.getSize(),
			attachment.getContentType(),
			"/api/v1/posts/" + postId + "/attachments/" + attachment.getId()
		);
	}

	private BoardReplyDto toReplyDto(BoardReply reply) {
		return new BoardReplyDto(
			reply.getId(),
			reply.getBody(),
			reply.isAi(),
			reply.getAiProvider(),
			reply.getAiModel(),
			reply.getCreatedAt(),
			reply.getUpdatedAt()
		);
	}
}
