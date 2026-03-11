package com.llm.app.board.controller;

import com.llm.app.board.dto.BoardPasswordRequest;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.BoardPostListResponse;
import com.llm.app.board.dto.CreateAiReplyRequest;
import com.llm.app.board.dto.CreateBoardPostRequest;
import com.llm.app.board.dto.CreateBoardReplyRequest;
import com.llm.app.board.dto.UpdateBoardPostRequest;
import com.llm.app.board.dto.UpdateBoardReplyRequest;
import com.llm.app.board.service.BoardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts")
public class BoardPostController {
	private final BoardService boardService;

	public BoardPostController(BoardService boardService) {
		this.boardService = boardService;
	}

	@GetMapping
	public BoardPostListResponse getPosts(@RequestParam(defaultValue = "1") int page) {
		return boardService.getPosts(page);
	}

	@GetMapping("/{id}")
	public BoardPostDetailResponse getPost(@PathVariable Long id) {
		return boardService.getPost(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createPost(@Valid @RequestBody CreateBoardPostRequest request) {
		return boardService.createPost(request);
	}

	@PutMapping("/{id}")
	public BoardPostDetailResponse updatePost(
		@PathVariable Long id,
		@Valid @RequestBody UpdateBoardPostRequest request
	) {
		return boardService.updatePost(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePost(@PathVariable Long id, @Valid @RequestBody BoardPasswordRequest request) {
		boardService.deletePost(id, request);
	}

	@PostMapping("/{id}/replies")
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createReply(
		@PathVariable Long id,
		@Valid @RequestBody CreateBoardReplyRequest request
	) {
		return boardService.createReply(id, request);
	}

	@PostMapping("/{id}/ai-replies")
	@ResponseStatus(HttpStatus.CREATED)
	public BoardPostDetailResponse createAiReply(
		@PathVariable Long id,
		@Valid @RequestBody CreateAiReplyRequest request
	) {
		return boardService.createAiReply(id, request);
	}

	@PutMapping("/replies/{replyId}")
	public BoardPostDetailResponse updateReply(
		@PathVariable Long replyId,
		@Valid @RequestBody UpdateBoardReplyRequest request
	) {
		return boardService.updateReply(replyId, request);
	}

	@DeleteMapping("/replies/{replyId}")
	public BoardPostDetailResponse deleteReply(
		@PathVariable Long replyId,
		@Valid @RequestBody BoardPasswordRequest request
	) {
		return boardService.deleteReply(replyId, request);
	}
}
