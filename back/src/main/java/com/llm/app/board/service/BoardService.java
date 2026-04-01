package com.llm.app.board.service;

import com.llm.app.board.ai.AiProvider;
import com.llm.app.board.ai.AiReplyGenerator;
import java.util.List;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.BoardPostListResponse;
import com.llm.app.board.dto.CreateAiReplyRequest;
import com.llm.app.board.dto.CreateBoardPostRequest;
import com.llm.app.board.dto.CreateBoardReplyRequest;
import com.llm.app.board.dto.UpdateBoardPostRequest;
import com.llm.app.board.dto.UpdateBoardReplyRequest;
import com.llm.app.board.exception.AiReplyModificationNotAllowedException;
import com.llm.app.board.exception.AiReplyNotAllowedException;
import com.llm.app.board.exception.BoardPostNotFoundException;
import com.llm.app.board.exception.BoardReplyNotFoundException;
import com.llm.app.board.exception.BoardAttachmentNotFoundException;
import com.llm.app.board.exception.FileConversionLockedException;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import com.llm.app.board.exception.InvalidFileConversionRequestException;
import com.llm.app.board.model.BoardAttachment;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardPostMode;
import com.llm.app.board.model.BoardReply;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.repository.BoardPostSummaryProjection;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
public class BoardService {
	private static final int POSTS_PAGE_SIZE = 10;

	private final BoardPostRepository boardPostRepository;
	private final BoardReplyRepository boardReplyRepository;
	private final BoardAttachmentRepository boardAttachmentRepository;
	private final BoardContentCodec boardContentCodec;
	private final BoardMapper boardMapper;
	private final AiReplyGenerator aiReplyGenerator;
	private final AttachmentStorageService attachmentStorageService;

	public BoardService(
		BoardPostRepository boardPostRepository,
		BoardReplyRepository boardReplyRepository,
		BoardAttachmentRepository boardAttachmentRepository,
		BoardContentCodec boardContentCodec,
		BoardMapper boardMapper,
		AiReplyGenerator aiReplyGenerator,
		AttachmentStorageService attachmentStorageService
	) {
		this.boardPostRepository = boardPostRepository;
		this.boardReplyRepository = boardReplyRepository;
		this.boardAttachmentRepository = boardAttachmentRepository;
		this.boardContentCodec = boardContentCodec;
		this.boardMapper = boardMapper;
		this.aiReplyGenerator = aiReplyGenerator;
		this.attachmentStorageService = attachmentStorageService;
	}

	@Transactional(readOnly = true)
	public BoardPostListResponse getPosts(int page, String query) {
		int pageNumber = Math.max(page, 1);
		String keyword = toKeywordPattern(query);
		Page<BoardPostSummaryProjection> posts = boardPostRepository.findPostSummaries(
			keyword,
			PageRequest.of(pageNumber - 1, POSTS_PAGE_SIZE)
		);
		return boardMapper.toListResponse(posts);
	}

	@Transactional(readOnly = true)
	public BoardPostDetailResponse getPost(Long id) {
		return toDetailResponse(findPostWithReplies(id));
	}

	public BoardPostDetailResponse createPost(CreateBoardPostRequest request) {
		Instant now = Instant.now();
		BoardPostMode mode = request.getMode();
		validateAttachmentRules(mode, request.getAttachment(), false, Optional.empty());
		BoardPost savedPost = boardPostRepository.save(new BoardPost(
			request.getTitle().trim(),
			resolvePostBody(mode, request.getBodyBase64()),
			mode,
			now,
			now
		));
		replaceAttachment(savedPost, request.getAttachment(), false, now);
		return toDetailResponse(savedPost);
	}

	public BoardPostDetailResponse updatePost(Long id, UpdateBoardPostRequest request) {
		BoardPost post = findPostWithReplies(id);
		ensurePostIsEditable(post);
		BoardPostMode mode = request.getMode();
		validateAttachmentRules(mode, request.getAttachment(), request.isRemoveAttachment(), findAttachment(post.getId()));
		post.update(
			request.getTitle().trim(),
			resolvePostBody(mode, request.getBodyBase64()),
			mode,
			Instant.now()
		);
		replaceAttachment(post, request.getAttachment(), request.isRemoveAttachment(), Instant.now());
		return toDetailResponse(post);
	}

	public void deletePost(Long id) {
		BoardPost post = findPostWithReplies(id);
		findAttachment(post.getId()).ifPresent(this::deleteAttachment);
		boardPostRepository.delete(post);
	}

	public void batchDeletePosts(List<Long> ids) {
		for (Long id : ids) {
			boardPostRepository.findById(id).ifPresent(post -> {
				boardAttachmentRepository.findByPost_Id(post.getId())
					.ifPresent(this::deleteAttachment);
				boardPostRepository.delete(post);
			});
		}
	}

	public BoardPostDetailResponse createReply(Long postId, CreateBoardReplyRequest request) {
		BoardPost post = findPostWithReplies(postId);
		Instant now = Instant.now();
		BoardReply reply = new BoardReply(
			post,
			boardContentCodec.decodeBody(request.bodyBase64()),
			now,
			now
		);
		post.getReplies().add(reply);
		boardReplyRepository.saveAndFlush(reply);
		return toDetailResponse(findPostWithReplies(postId));
	}

	public BoardPostDetailResponse convertPostToAttachment(Long postId) {
		BoardPost post = findPostWithReplies(postId);
		if (post.getMode() != BoardPostMode.FILE_CONVERSION_REQUEST) {
			throw new InvalidFileConversionRequestException("conversion is only allowed for file conversion request posts");
		}

		Optional<BoardAttachment> existingAttachment = findAttachment(postId);
		if (existingAttachment.isPresent()) {
			return toDetailResponse(post);
		}

		byte[] zipBytes = boardContentCodec.decodeBinary(post.getBody());
		AttachmentStorageService.StoredAttachment storedAttachment = attachmentStorageService.store(
			"post-" + postId + ".zip",
			"application/zip",
			zipBytes
		);
		boardAttachmentRepository.saveAndFlush(new BoardAttachment(
			post,
			storedAttachment.originalFilename(),
			storedAttachment.storedFilename(),
			storedAttachment.storagePath(),
			storedAttachment.contentType(),
			storedAttachment.size(),
			Instant.now()
		));
		return toDetailResponse(post);
	}

	public BoardPostDetailResponse createAiReply(Long postId, CreateAiReplyRequest request) {
		BoardPost post = findPostWithReplies(postId);
		if (post.getMode() == BoardPostMode.FILE_CONVERSION_REQUEST) {
			throw new AiReplyNotAllowedException();
		}
		AiProvider provider = AiProvider.from(request.provider());
		String generatedReply = aiReplyGenerator.generateReply(provider, post.getTitle(), post.getBody());
		Instant now = Instant.now();
		BoardReply reply = new BoardReply(
			post,
			generatedReply,
			now,
			now,
			true,
			provider.label()
		);
		post.getReplies().add(reply);
		boardReplyRepository.saveAndFlush(reply);
		return toDetailResponse(findPostWithReplies(postId));
	}

	public BoardPostDetailResponse updateReply(Long replyId, UpdateBoardReplyRequest request) {
		BoardReply reply = findReply(replyId);
		ensureReplyIsEditable(reply);
		reply.update(boardContentCodec.decodeBody(request.bodyBase64()), Instant.now());
		return toDetailResponse(findPostWithReplies(reply.getPost().getId()));
	}

	public void deleteReply(Long replyId) {
		BoardReply reply = findReply(replyId);
		ensureReplyIsEditable(reply);
		reply.getPost().getReplies().remove(reply);
		boardReplyRepository.delete(reply);
		boardReplyRepository.flush();
	}

	@Transactional(readOnly = true)
	public BoardAttachmentDownload downloadAttachment(Long postId) {
		BoardPost post = findPostWithReplies(postId);
		BoardAttachment attachment = findAttachment(post.getId())
			.orElseThrow(() -> new BoardAttachmentNotFoundException(postId));
		return new BoardAttachmentDownload(
			attachmentStorageService.loadAsResource(attachment),
			attachment.getOriginalFilename(),
			attachment.getContentType(),
			attachment.getSize()
		);
	}

	private BoardPost findPostWithReplies(Long id) {
		return boardPostRepository.findWithRepliesById(id)
			.orElseThrow(() -> new BoardPostNotFoundException(id));
	}

	private BoardReply findReply(Long id) {
		return boardReplyRepository.findById(id)
			.orElseThrow(() -> new BoardReplyNotFoundException(id));
	}

	private void ensureReplyIsEditable(BoardReply reply) {
		if (reply.isAi()) {
			throw new AiReplyModificationNotAllowedException();
		}
	}

	private void ensurePostIsEditable(BoardPost post) {
		if (post.getMode() == BoardPostMode.FILE_CONVERSION_REQUEST && findAttachment(post.getId()).isPresent()) {
			throw new FileConversionLockedException(post.getId());
		}
	}

	private BoardPostDetailResponse toDetailResponse(BoardPost post) {
		return boardMapper.toDetailResponse(post, findAttachment(post.getId()).orElse(null));
	}

	private Optional<BoardAttachment> findAttachment(Long postId) {
		return boardAttachmentRepository.findByPost_Id(postId);
	}

	private String toKeywordPattern(String query) {
		if (!StringUtils.hasText(query)) {
			return null;
		}

		return "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
	}

	private String resolvePostBody(BoardPostMode mode, String bodyBase64) {
		if (mode == BoardPostMode.FILE_CONVERSION_REQUEST) {
			return bodyBase64;
		}
		return boardContentCodec.decodeBody(bodyBase64);
	}

	private void validateAttachmentRules(
		BoardPostMode mode,
		MultipartFile attachment,
		boolean removeAttachment,
		Optional<BoardAttachment> existingAttachment
	) {
		if (mode != BoardPostMode.FILE_CONVERSION_REQUEST) {
			return;
		}

		boolean hasNewAttachment = hasAttachmentUpload(attachment);
		if (hasNewAttachment) {
			throw new InvalidAttachmentRequestException("attachment is not allowed for file conversion request posts");
		}
		if (existingAttachment.isPresent() && !removeAttachment) {
			throw new InvalidAttachmentRequestException(
				"existing attachment must be removed before changing to file conversion request mode"
			);
		}
	}

	private void replaceAttachment(BoardPost post, MultipartFile attachment, boolean removeAttachment, Instant now) {
		boolean hasNewAttachment = hasAttachmentUpload(attachment);
		if (removeAttachment && hasNewAttachment) {
			throw new InvalidAttachmentRequestException("removeAttachment cannot be true when attachment is uploaded");
		}

		Optional<BoardAttachment> existingAttachment = findAttachment(post.getId());
		if (removeAttachment) {
			existingAttachment.ifPresent(this::deleteAttachment);
			return;
		}

		if (!hasNewAttachment) {
			return;
		}

		existingAttachment.ifPresent(this::deleteAttachment);
		AttachmentStorageService.StoredAttachment storedAttachment = attachmentStorageService.store(attachment);
		boardAttachmentRepository.save(new BoardAttachment(
			post,
			storedAttachment.originalFilename(),
			storedAttachment.storedFilename(),
			storedAttachment.storagePath(),
			storedAttachment.contentType(),
			storedAttachment.size(),
			now
		));
	}

	private void deleteAttachment(BoardAttachment attachment) {
		attachmentStorageService.deleteIfExists(attachment.getStoragePath());
		boardAttachmentRepository.delete(attachment);
		boardAttachmentRepository.flush();
	}

	private boolean hasAttachmentUpload(MultipartFile attachment) {
		return attachment != null && StringUtils.hasText(attachment.getOriginalFilename());
	}
}
