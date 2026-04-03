package com.llm.app.board.controller;

import com.llm.app.auth.InvalidCredentialsException;
import com.llm.app.auth.JwtProvider;
import com.llm.app.board.dto.BatchDeleteRequest;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.BoardPostListResponse;
import com.llm.app.board.dto.CreateAiReplyRequest;
import com.llm.app.board.dto.CreateBoardPostRequest;
import com.llm.app.board.dto.CreateBoardReplyRequest;
import com.llm.app.board.dto.UpdateBoardPostRequest;
import com.llm.app.board.dto.UpdateBoardReplyRequest;
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

	private void requireAuth(String authHeader) {
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			throw new InvalidCredentialsException("Authentication required");
		}
		jwtProvider.validateAndGetUsername(authHeader.substring(7));
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
		requireAuth(authHeader);
		return boardService.createPost(request);
	}

	@PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BoardPostDetailResponse updatePost(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id,
		@Valid @ModelAttribute UpdateBoardPostRequest request
	) {
		requireAuth(authHeader);
		return boardService.updatePost(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePost(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id
	) {
		requireAuth(authHeader);
		boardService.deletePost(id);
	}

	@PostMapping("/batch-delete")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void batchDeletePosts(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@Valid @RequestBody BatchDeleteRequest request
	) {
		requireAuth(authHeader);
		boardService.batchDeletePosts(request.ids());
	}

	@PostMapping("/{id}/replies")
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createReply(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id,
		@Valid @RequestBody CreateBoardReplyRequest request
	) {
		requireAuth(authHeader);
		return boardService.createReply(id, request);
	}

	@PostMapping("/{id}/ai-replies")
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createAiReply(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long id,
		@Valid @RequestBody CreateAiReplyRequest request
	) {
		requireAuth(authHeader);
		return boardService.createAiReply(id, request);
	}

	@GetMapping("/{id}/attachment")
	public ResponseEntity<Resource> downloadAttachment(@PathVariable Long id) {
		var attachment = boardService.downloadAttachment(id);
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
		requireAuth(authHeader);
		return boardService.updateReply(replyId, request);
	}

	@DeleteMapping("/replies/{replyId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteReply(
		@RequestHeader(value = "Authorization", required = false) String authHeader,
		@PathVariable Long replyId
	) {
		requireAuth(authHeader);
		boardService.deleteReply(replyId);
	}
}
