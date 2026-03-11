package com.llm.app.board.service;

import com.llm.app.board.ai.AiProvider;
import com.llm.app.board.ai.AiReplyGenerator;
import com.llm.app.board.dto.BoardPasswordRequest;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.BoardPostListResponse;
import com.llm.app.board.dto.CreateAiReplyRequest;
import com.llm.app.board.dto.CreateBoardPostRequest;
import com.llm.app.board.dto.CreateBoardReplyRequest;
import com.llm.app.board.dto.UpdateBoardPostRequest;
import com.llm.app.board.dto.UpdateBoardReplyRequest;
import com.llm.app.board.exception.AiReplyModificationNotAllowedException;
import com.llm.app.board.exception.BoardPostNotFoundException;
import com.llm.app.board.exception.BoardReplyNotFoundException;
import com.llm.app.board.exception.InvalidBoardPasswordException;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardReply;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.repository.BoardPostSummaryProjection;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BoardService {
	private static final int POSTS_PAGE_SIZE = 10;

	private final BoardPostRepository boardPostRepository;
	private final BoardReplyRepository boardReplyRepository;
	private final BoardContentCodec boardContentCodec;
	private final BoardMapper boardMapper;
	private final PasswordEncoder passwordEncoder;
	private final AiReplyGenerator aiReplyGenerator;

	public BoardService(
		BoardPostRepository boardPostRepository,
		BoardReplyRepository boardReplyRepository,
		BoardContentCodec boardContentCodec,
		BoardMapper boardMapper,
		PasswordEncoder passwordEncoder,
		AiReplyGenerator aiReplyGenerator
	) {
		this.boardPostRepository = boardPostRepository;
		this.boardReplyRepository = boardReplyRepository;
		this.boardContentCodec = boardContentCodec;
		this.boardMapper = boardMapper;
		this.passwordEncoder = passwordEncoder;
		this.aiReplyGenerator = aiReplyGenerator;
	}

	@Transactional(readOnly = true)
	public BoardPostListResponse getPosts(int page) {
		int pageNumber = Math.max(page, 1);
		Page<BoardPostSummaryProjection> posts = boardPostRepository.findPostSummaries(
			PageRequest.of(pageNumber - 1, POSTS_PAGE_SIZE)
		);
		return boardMapper.toListResponse(posts);
	}

	@Transactional(readOnly = true)
	public BoardPostDetailResponse getPost(Long id) {
		return boardMapper.toDetailResponse(findPostWithReplies(id));
	}

	public BoardPostDetailResponse createPost(CreateBoardPostRequest request) {
		Instant now = Instant.now();
		BoardPost savedPost = boardPostRepository.save(new BoardPost(
			request.title().trim(),
			boardContentCodec.decodeBody(request.bodyBase64()),
			passwordEncoder.encode(request.password()),
			now,
			now
		));
		return boardMapper.toDetailResponse(savedPost);
	}

	public BoardPostDetailResponse updatePost(Long id, UpdateBoardPostRequest request) {
		BoardPost post = findPostWithReplies(id);
		verifyPassword(request.password(), post.getPasswordHash());
		post.update(
			request.title().trim(),
			boardContentCodec.decodeBody(request.bodyBase64()),
			Instant.now()
		);
		return boardMapper.toDetailResponse(post);
	}

	public void deletePost(Long id, BoardPasswordRequest request) {
		BoardPost post = findPostWithReplies(id);
		verifyPassword(request.password(), post.getPasswordHash());
		boardPostRepository.delete(post);
	}

	public BoardPostDetailResponse createReply(Long postId, CreateBoardReplyRequest request) {
		BoardPost post = findPostWithReplies(postId);
		Instant now = Instant.now();
		BoardReply reply = new BoardReply(
			post,
			boardContentCodec.decodeBody(request.bodyBase64()),
			passwordEncoder.encode(request.password()),
			now,
			now
		);
		post.getReplies().add(reply);
		boardReplyRepository.saveAndFlush(reply);
		return boardMapper.toDetailResponse(findPostWithReplies(postId));
	}

	public BoardPostDetailResponse createAiReply(Long postId, CreateAiReplyRequest request) {
		BoardPost post = findPostWithReplies(postId);
		AiProvider provider = AiProvider.from(request.provider());
		String generatedReply = aiReplyGenerator.generateReply(provider, post.getTitle(), post.getBody());
		Instant now = Instant.now();
		BoardReply reply = new BoardReply(
			post,
			generatedReply,
			passwordEncoder.encode(UUID.randomUUID().toString()),
			now,
			now,
			true,
			provider.label()
		);
		post.getReplies().add(reply);
		boardReplyRepository.saveAndFlush(reply);
		return boardMapper.toDetailResponse(findPostWithReplies(postId));
	}

	public BoardPostDetailResponse updateReply(Long replyId, UpdateBoardReplyRequest request) {
		BoardReply reply = findReply(replyId);
		ensureReplyIsEditable(reply);
		verifyPassword(request.password(), reply.getPasswordHash());
		reply.update(boardContentCodec.decodeBody(request.bodyBase64()), Instant.now());
		return boardMapper.toDetailResponse(findPostWithReplies(reply.getPost().getId()));
	}

	public BoardPostDetailResponse deleteReply(Long replyId, BoardPasswordRequest request) {
		BoardReply reply = findReply(replyId);
		ensureReplyIsEditable(reply);
		verifyPassword(request.password(), reply.getPasswordHash());
		Long postId = reply.getPost().getId();
		reply.getPost().getReplies().remove(reply);
		boardReplyRepository.delete(reply);
		boardReplyRepository.flush();
		return boardMapper.toDetailResponse(findPostWithReplies(postId));
	}

	private BoardPost findPostWithReplies(Long id) {
		return boardPostRepository.findWithRepliesById(id)
			.orElseThrow(() -> new BoardPostNotFoundException(id));
	}

	private BoardReply findReply(Long id) {
		return boardReplyRepository.findById(id)
			.orElseThrow(() -> new BoardReplyNotFoundException(id));
	}

	private void verifyPassword(String rawPassword, String passwordHash) {
		if (!passwordEncoder.matches(rawPassword, passwordHash)) {
			throw new InvalidBoardPasswordException();
		}
	}

	private void ensureReplyIsEditable(BoardReply reply) {
		if (reply.isAi()) {
			throw new AiReplyModificationNotAllowedException();
		}
	}
}
