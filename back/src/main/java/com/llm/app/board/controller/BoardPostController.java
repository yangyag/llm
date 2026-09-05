package com.llm.app.board.controller;

import com.llm.app.auth.JwtProvider;
import com.llm.app.board.dto.BatchDeleteRequest;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.BoardPostListResponse;
import com.llm.app.board.dto.CreateAiReplyRequest;
import com.llm.app.board.dto.CreateBoardPostRequest;
import com.llm.app.board.dto.CreateBoardReplyRequest;
import com.llm.app.board.dto.UpdateBoardPostRequest;
import com.llm.app.board.dto.UpdateBoardReplyRequest;
import com.llm.app.board.exception.AiReplyDisabledException;
import com.llm.app.board.service.BoardService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/v1/posts")
public class BoardPostController {
	private final BoardService boardService;
	private final JwtProvider jwtProvider;

	public BoardPostController(BoardService boardService, JwtProvider jwtProvider) {
		this.boardService = boardService;
		this.jwtProvider = jwtProvider;
	}

	@GetMapping
	public BoardPostListResponse getPosts(
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(required = false) String query
	) {
		return boardService.getPosts(page, query);
	}

	@GetMapping("/{id}")
	public BoardPostDetailResponse getPost(@PathVariable Long id) {
		return boardService.getPost(id);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createPost(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@Valid @ModelAttribute CreateBoardPostRequest request
	) {
		Long userId = jwtProvider.authenticate(authHeader);
		return boardService.createPost(userId, request);
	}

	@PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BoardPostDetailResponse updatePost(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id,
		@Valid @ModelAttribute UpdateBoardPostRequest request
	) {
		Long userId = jwtProvider.authenticate(authHeader);
		return boardService.updatePost(userId, id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePost(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id
	) {
		Long userId = jwtProvider.authenticate(authHeader);
		boardService.deletePost(userId, id);
	}

	@PostMapping("/batch-delete")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void batchDeletePosts(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@Valid @RequestBody BatchDeleteRequest request
	) {
		Long userId = jwtProvider.authenticate(authHeader);
		boardService.batchDeletePosts(userId, request.ids());
	}

	@PostMapping("/{id}/replies")
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createReply(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id,
		@Valid @RequestBody CreateBoardReplyRequest request
	) {
		Long userId = jwtProvider.authenticate(authHeader);
		return boardService.createReply(userId, id, request);
	}

	/**
	 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료.
	 *             410 Gone을 반환하는 비활성 스텁. 매핑은 원인 파악용으로 유지되며 BoardService 호출은 하지 않는다.
	 */
	@Deprecated
	@PostMapping("/{id}/ai-replies")
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createAiReply(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id,
		@Valid @RequestBody CreateAiReplyRequest request
	) {
		jwtProvider.authenticate(authHeader);
		throw new AiReplyDisabledException();
	}

	@GetMapping("/{id}/attachments/{attachmentId}")
	public ResponseEntity<Resource> downloadAttachment(
		@PathVariable Long id,
		@PathVariable Long attachmentId
	) {
		var attachment = boardService.downloadAttachment(id, attachmentId);
		MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
		if (attachment.contentType() != null && !attachment.contentType().isBlank()) {
			mediaType = MediaType.parseMediaType(attachment.contentType());
		}
		return ResponseEntity.ok()
			.contentType(mediaType)
			.contentLength(attachment.size())
			.header(
				org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment()
					.filename(attachment.originalFilename(), StandardCharsets.UTF_8)
					.build()
					.toString()
			)
			.body(attachment.resource());
	}

	@PutMapping("/replies/{replyId}")
	public BoardPostDetailResponse updateReply(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long replyId,
		@Valid @RequestBody UpdateBoardReplyRequest request
	) {
		Long userId = jwtProvider.authenticate(authHeader);
		return boardService.updateReply(userId, replyId, request);
	}

	@DeleteMapping("/replies/{replyId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteReply(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long replyId
	) {
		Long userId = jwtProvider.authenticate(authHeader);
		boardService.deleteReply(userId, replyId);
	}
}
