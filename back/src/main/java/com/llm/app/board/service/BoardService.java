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
import com.llm.app.board.exception.InvalidFileConversionRequestException;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import com.llm.app.board.model.BoardAttachment;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardPostMode;
import com.llm.app.board.model.BoardReply;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.BoardReplyRepository;
import com.llm.app.board.repository.BoardPostSummaryProjection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
	private final int maxAttachmentsPerPost;

	public BoardService(
		BoardPostRepository boardPostRepository,
		BoardReplyRepository boardReplyRepository,
		BoardAttachmentRepository boardAttachmentRepository,
		BoardContentCodec boardContentCodec,
		BoardMapper boardMapper,
		AiReplyGenerator aiReplyGenerator,
		AttachmentStorageService attachmentStorageService,
		@Value("${app.attachments.max-count:5}") int maxAttachmentsPerPost
	) {
		this.boardPostRepository = boardPostRepository;
		this.boardReplyRepository = boardReplyRepository;
		this.boardAttachmentRepository = boardAttachmentRepository;
		this.boardContentCodec = boardContentCodec;
		this.boardMapper = boardMapper;
		this.aiReplyGenerator = aiReplyGenerator;
		this.attachmentStorageService = attachmentStorageService;
		this.maxAttachmentsPerPost = maxAttachmentsPerPost;
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
		validateAttachmentRules(mode);
		BoardPost savedPost = boardPostRepository.save(new BoardPost(
			request.getTitle().trim(),
			resolvePostBody(mode, request.getBodyBase64()),
			mode,
			now,
			now
		));
		syncAttachments(savedPost, request.getAttachments(), null, now);
		return toDetailResponse(savedPost);
	}

	public BoardPostDetailResponse updatePost(Long id, UpdateBoardPostRequest request) {
		BoardPost post = findPostWithReplies(id);
		ensurePostIsEditable(post);
		BoardPostMode mode = request.getMode();
		validateAttachmentRules(mode);
		post.update(
			request.getTitle().trim(),
			resolvePostBody(mode, request.getBodyBase64()),
			mode,
			Instant.now()
		);
		syncAttachments(post, request.getAttachments(), request.getRemoveAttachmentIds(), Instant.now());
		return toDetailResponse(post);
	}

	public void deletePost(Long id) {
		BoardPost post = findPostWithReplies(id);
		findAttachments(post.getId()).forEach(this::deleteAttachment);
		boardPostRepository.delete(post);
	}

	public void batchDeletePosts(List<Long> ids) {
		for (Long id : ids) {
			boardPostRepository.findById(id).ifPresent(post -> {
				findAttachments(post.getId()).forEach(this::deleteAttachment);
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

	public BoardPostDetailResponse createAiReply(Long postId, CreateAiReplyRequest request) {
		BoardPost post = findPostWithReplies(postId);
		if (post.getMode() == BoardPostMode.FILE_CONVERSION_REQUEST) {
			throw new AiReplyNotAllowedException();
		}
		AiProvider provider = AiProvider.from(request.provider());
		AiReplyGenerator.AiReplyResult result = aiReplyGenerator.generateReply(provider, post.getTitle(), post.getBody());
		Instant now = Instant.now();
		BoardReply reply = new BoardReply(
			post,
			result.content(),
			now,
			now,
			true,
			provider.label(),
			result.model()
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
	public BoardAttachmentDownload downloadAttachment(Long postId, Long attachmentId) {
		BoardPost post = findPostWithReplies(postId);
		BoardAttachment attachment = boardAttachmentRepository.findByIdAndPost_Id(attachmentId, post.getId())
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
		if (post.getMode() == BoardPostMode.FILE_CONVERSION_REQUEST && !findAttachments(post.getId()).isEmpty()) {
			throw new FileConversionLockedException(post.getId());
		}
	}

	private BoardPostDetailResponse toDetailResponse(BoardPost post) {
		return boardMapper.toDetailResponse(post, findAttachments(post.getId()));
	}

	private List<BoardAttachment> findAttachments(Long postId) {
		return boardAttachmentRepository.findByPost_IdOrderByCreatedAtAscIdAsc(postId);
	}

	private String toKeywordPattern(String query) {
		if (!StringUtils.hasText(query)) {
			return null;
		}

		return "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
	}

	private String resolvePostBody(BoardPostMode mode, String bodyBase64) {
		ensureManualPostMode(mode);
		return boardContentCodec.decodeOptionalBody(bodyBase64);
	}

	private void validateAttachmentRules(BoardPostMode mode) {
		ensureManualPostMode(mode);
	}

	private void ensureManualPostMode(BoardPostMode mode) {
		if (mode == BoardPostMode.FILE_CONVERSION_REQUEST) {
			throw new InvalidFileConversionRequestException(
				"manual file conversion request posts are not supported; use the upload session API"
			);
		}
	}

	private void syncAttachments(
		BoardPost post,
		List<MultipartFile> uploads,
		Collection<Long> removeAttachmentIds,
		Instant now
	) {
		List<BoardAttachment> existing = findAttachments(post.getId());

		// 1) 삭제 대상만 확정한다(아직 삭제하지 않음).
		Set<Long> removeIds = removeAttachmentIds == null
			? Set.of()
			: new HashSet<>(removeAttachmentIds);
		List<BoardAttachment> toRemove = removeIds.isEmpty()
			? List.of()
			: existing.stream()
				.filter(attachment -> removeIds.contains(attachment.getId()))
				.toList();
		if (toRemove.size() != removeIds.size()) {
			throw new InvalidAttachmentRequestException(
				"removeAttachmentIds references attachments that do not belong to this post"
			);
		}

		// 2) 신규 업로드만 필터링한다(아직 저장하지 않음).
		List<MultipartFile> newUploads = (uploads == null ? List.<MultipartFile>of() : uploads).stream()
			.filter(this::hasAttachmentUpload)
			.toList();

		// 3) 어떤 디스크/DB 변경보다 먼저 최종 개수를 검증한다 → 실패해도 부수효과가 전혀 없다.
		if (existing.size() - toRemove.size() + newUploads.size() > maxAttachmentsPerPost) {
			throw new InvalidAttachmentRequestException(
				"a post can have at most " + maxAttachmentsPerPost + " attachments"
			);
		}

		if (toRemove.isEmpty() && newUploads.isEmpty()) {
			return;
		}

		// 4) 신규 파일을 먼저 저장한다. 저장 중 실패하면 이번에 저장한 파일만 정리하고 중단하므로
		//    기존 첨부(toRemove 포함)는 그대로 보존되어 트랜잭션 롤백과 디스크 상태가 일관된다.
		List<String> storedPaths = new ArrayList<>();
		try {
			for (MultipartFile upload : newUploads) {
				AttachmentStorageService.StoredAttachment stored = attachmentStorageService.store(upload);
				storedPaths.add(stored.storagePath());
				boardAttachmentRepository.save(new BoardAttachment(
					post,
					stored.originalFilename(),
					stored.storedFilename(),
					stored.storagePath(),
					stored.contentType(),
					stored.size(),
					now
				));
			}
		} catch (RuntimeException exception) {
			storedPaths.forEach(attachmentStorageService::deleteIfExists);
			throw exception;
		}

		// 5) 신규 저장이 모두 끝난 뒤에야 기존 첨부를 삭제한다.
		toRemove.forEach(this::deleteAttachment);
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
