package com.llm.app.board.service;

import com.llm.app.auth.api.AuthenticatedUser;
import com.llm.app.auth.api.AuthenticationGateway;
import com.llm.app.auth.api.ForbiddenException;
import com.llm.app.auth.api.IdentityAccess;
import com.llm.app.auth.api.InvalidCredentialsException;
import com.llm.app.auth.api.UserRole;
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
import com.llm.app.board.exception.FileConversionLockedException;
import com.llm.app.board.exception.NotFoundException;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
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
	private final IdentityAccess identityAccess;
	private final BoardContentCodec boardContentCodec;
	private final BoardMapper boardMapper;
	private final AiReplyGenerator aiReplyGenerator;
	private final AttachmentStorageService attachmentStorageService;
	private final AttachmentFileLifecycle attachmentFileLifecycle;
	private final int maxAttachmentsPerPost;

	/**
	 * 게시판 서비스가 사용하는 저장소와 협력 객체를 초기화한다.
	 *
	 * @param boardPostRepository 게시글 저장소
	 * @param boardReplyRepository 댓글 저장소
	 * @param boardAttachmentRepository 첨부파일 메타데이터 저장소
	 * @param identityAccess 사용자 인증·조회 객체
	 * @param boardContentCodec 게시글 본문 인코더·디코더
	 * @param boardMapper 게시글 응답 변환기
	 * @param aiReplyGenerator 레거시 AI 답변 생성기
	 * @param attachmentStorageService 첨부파일 저장소
	 * @param attachmentFileLifecycle 첨부파일 생명주기 관리 객체
	 * @param maxAttachmentsPerPost 게시글별 최대 첨부파일 수
	 */
	public BoardService(
		BoardPostRepository boardPostRepository,
		BoardReplyRepository boardReplyRepository,
		BoardAttachmentRepository boardAttachmentRepository,
		IdentityAccess identityAccess,
		BoardContentCodec boardContentCodec,
		BoardMapper boardMapper,
		AiReplyGenerator aiReplyGenerator,
		AttachmentStorageService attachmentStorageService,
		AttachmentFileLifecycle attachmentFileLifecycle,
		@Value("${app.attachments.max-count:5}") int maxAttachmentsPerPost
	) {
		this.boardPostRepository = boardPostRepository;
		this.boardReplyRepository = boardReplyRepository;
		this.boardAttachmentRepository = boardAttachmentRepository;
		this.identityAccess = identityAccess;
		this.boardContentCodec = boardContentCodec;
		this.boardMapper = boardMapper;
		this.aiReplyGenerator = aiReplyGenerator;
		this.attachmentStorageService = attachmentStorageService;
		this.attachmentFileLifecycle = attachmentFileLifecycle;
		this.maxAttachmentsPerPost = maxAttachmentsPerPost;
	}

	/**
	 * 검색어에 해당하는 게시글 목록을 페이지 단위로 조회한다.
	 *
	 * @param page 요청 페이지 번호(1보다 작으면 1로 보정)
	 * @param query 제목 또는 본문 검색어
	 * @return 게시글 목록 응답
	 */
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

	/**
	 * 게시글과 댓글 및 첨부파일을 상세 조회한다.
	 *
	 * @param id 조회할 게시글 ID
	 * @return 게시글 상세 응답
	 */
	@Transactional(readOnly = true)
	public BoardPostDetailResponse getPost(Long id) {
		return toDetailResponse(findPostWithReplies(id));
	}

	/**
	 * 로그인한 사용자의 게시글을 생성한다.
	 *
	 * @param authorUserId 작성자 사용자 ID
	 * @param request 게시글 생성 요청
	 * @return 생성된 게시글 상세 응답
	 */
	public BoardPostDetailResponse createPost(Long authorUserId, CreateBoardPostRequest request) {
		AuthenticatedUser author = requireExistingUser(authorUserId);
		Instant now = Instant.now();
		BoardPostMode mode = request.getMode();
		BoardPost savedPost = boardPostRepository.save(new BoardPost(
			request.getTitle().trim(),
			resolvePostBody(mode, request.getBodyBase64()),
			mode,
			author.username(),
			now,
			now,
			author.userId()
		));
		syncAttachments(savedPost, request.getAttachments(), null, now);
		return toDetailResponse(savedPost);
	}

	/**
	 * 게시글을 수정한다.
	 *
	 * @param actorUserId 수정 요청을 수행한 사용자 ID
	 * @param id 수정할 게시글 ID
	 * @param request 게시글 수정 요청
	 * @return 수정된 게시글 상세 응답
	 */
	public BoardPostDetailResponse updatePost(Long actorUserId, Long id, UpdateBoardPostRequest request) {
		BoardPost post = findPostWithReplies(id);
		ensureCanManagePost(actorUserId, post);
		ensurePostIsEditable(post);
		BoardPostMode mode = request.getMode();
		post.update(
			request.getTitle().trim(),
			resolvePostBody(mode, request.getBodyBase64()),
			mode,
			Instant.now()
		);
		syncAttachments(post, request.getAttachments(), request.getRemoveAttachmentIds(), Instant.now());
		return toDetailResponse(post);
	}

	/**
	 * 게시글과 그 첨부파일을 삭제한다.
	 *
	 * @param actorUserId 삭제 요청을 수행한 사용자 ID
	 * @param id 삭제할 게시글 ID
	 */
	public void deletePost(Long actorUserId, Long id) {
		BoardPost post = findPostWithReplies(id);
		ensureCanManagePost(actorUserId, post);
		deletePostEntity(post);
	}

	/**
	 * 여러 게시글을 한 번에 삭제한다.
	 *
	 * <p>대상 중 하나라도 삭제 권한이 없으면 어떤 게시글도 삭제하지 않는다.</p>
	 *
	 * @param actorUserId 삭제 요청을 수행한 사용자 ID
	 * @param ids 삭제할 게시글 ID 목록
	 */
	public void batchDeletePosts(Long actorUserId, List<Long> ids) {
		AuthenticatedUser actor = requireExistingUser(actorUserId);
		boolean admin = actor.role() == UserRole.ADMIN;
		List<BoardPost> posts = boardPostRepository.findAllById(ids);
		// 하나라도 권한이 없으면 어떤 글도 지우지 않는다.
		for (BoardPost post : posts) {
			if (!admin && !isOwner(actorUserId, post.getAuthorUserId())) {
				throw new ForbiddenException("작성자 본인 또는 관리자만 삭제할 수 있습니다.");
			}
		}
		posts.forEach(this::deletePostEntity);
	}

	/**
	 * 게시글 엔티티와 연결된 첨부파일을 내부적으로 삭제한다.
	 *
	 * @param post 삭제할 게시글 엔티티
	 */
	private void deletePostEntity(BoardPost post) {
		findAttachments(post.getId()).forEach(this::deleteAttachment);
		boardPostRepository.delete(post);
	}

	/**
	 * 게시글에 일반 댓글을 생성한다.
	 *
	 * @param authorUserId 작성자 사용자 ID
	 * @param postId 댓글을 작성할 게시글 ID
	 * @param request 댓글 생성 요청
	 * @return 댓글이 반영된 게시글 상세 응답
	 */
	public BoardPostDetailResponse createReply(Long authorUserId, Long postId, CreateBoardReplyRequest request) {
		AuthenticatedUser author = requireExistingUser(authorUserId);
		BoardPost post = findPostWithReplies(postId);
		Instant now = Instant.now();
		BoardReply reply = new BoardReply(
			post,
			boardContentCodec.decodeBody(request.bodyBase64()),
			author.username(),
			now,
			now,
			author.userId()
		);
		post.getReplies().add(reply);
		boardReplyRepository.saveAndFlush(reply);
		return toDetailResponse(findPostWithReplies(postId));
	}

	/**
	 * 레거시 AI 댓글을 생성한다.
	 *
	 * <p>AI 답변 기능 종료 이후에는 신규 호출하지 않으며, 기존 데이터 조회와 보호를
	 * 위해 메서드와 관련 로직만 유지한다.</p>
	 *
	 * @param postId AI 답변을 생성할 게시글 ID
	 * @param request AI 답변 생성 요청
	 * @return AI 댓글이 반영된 게시글 상세 응답
	 * @deprecated 2026-09-03 이후 AI 답변 기능이 종료되어 사용하지 않는다.
	 */
	@Deprecated
	public BoardPostDetailResponse createAiReply(Long postId, CreateAiReplyRequest request) {
		// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 컨트롤러가 410 스텁으로 가로막으므로 도달 불가. 본문은 잔재로 유지.
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
			null,
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

	/**
	 * 일반 댓글을 수정한다.
	 *
	 * @param actorUserId 수정 요청을 수행한 사용자 ID
	 * @param replyId 수정할 댓글 ID
	 * @param request 댓글 수정 요청
	 * @return 수정된 댓글이 반영된 게시글 상세 응답
	 */
	public BoardPostDetailResponse updateReply(Long actorUserId, Long replyId, UpdateBoardReplyRequest request) {
		BoardReply reply = findReply(replyId);
		ensureReplyIsEditable(reply);
		ensureCanManageReply(actorUserId, reply);
		reply.update(boardContentCodec.decodeBody(request.bodyBase64()), Instant.now());
		return toDetailResponse(findPostWithReplies(reply.getPost().getId()));
	}

	/**
	 * 일반 댓글을 삭제한다.
	 *
	 * @param actorUserId 삭제 요청을 수행한 사용자 ID
	 * @param replyId 삭제할 댓글 ID
	 */
	public void deleteReply(Long actorUserId, Long replyId) {
		BoardReply reply = findReply(replyId);
		ensureReplyIsEditable(reply);
		ensureCanManageReply(actorUserId, reply);
		reply.getPost().getReplies().remove(reply);
		boardReplyRepository.delete(reply);
		boardReplyRepository.flush();
	}

	/**
	 * 게시글에 연결된 첨부파일을 다운로드할 수 있는 정보로 조회한다.
	 *
	 * @param postId 첨부파일이 속한 게시글 ID
	 * @param attachmentId 다운로드할 첨부파일 ID
	 * @return 첨부파일 리소스와 다운로드 메타데이터
	 */
	@Transactional(readOnly = true)
	public BoardAttachmentDownload downloadAttachment(Long postId, Long attachmentId) {
		BoardPost post = findPostWithReplies(postId);
		BoardAttachment attachment = boardAttachmentRepository.findByIdAndPost_Id(attachmentId, post.getId())
			.orElseThrow(() -> NotFoundException.attachment(postId));
		return new BoardAttachmentDownload(
			attachmentStorageService.loadAsResource(attachment),
			attachment.getOriginalFilename(),
			attachment.getContentType(),
			attachment.getSize()
		);
	}

	/**
	 * 댓글이 함께 조회된 게시글을 찾는다.
	 *
	 * @param id 조회할 게시글 ID
	 * @return 댓글이 함께 조회된 게시글
	 * @throws NotFoundException 게시글이 존재하지 않는 경우
	 */
	private BoardPost findPostWithReplies(Long id) {
		return boardPostRepository.findWithRepliesById(id)
			.orElseThrow(() -> NotFoundException.post(id));
	}

	/**
	 * 댓글을 ID로 조회한다.
	 *
	 * @param id 조회할 댓글 ID
	 * @return 조회된 댓글
	 * @throws NotFoundException 댓글이 존재하지 않는 경우
	 */
	private BoardReply findReply(Long id) {
		return boardReplyRepository.findById(id)
			.orElseThrow(() -> NotFoundException.reply(id));
	}

	/**
	 * AI 댓글의 수정·삭제 가능 여부를 확인한다.
	 *
	 * @param reply 수정 또는 삭제 대상 댓글
	 * @throws AiReplyModificationNotAllowedException AI 댓글인 경우
	 */
	private void ensureReplyIsEditable(BoardReply reply) {
		// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 AI 답변 행의 수정/삭제 잠금용으로 유지되며 삭제 금지.
		if (reply.isAi()) {
			throw new AiReplyModificationNotAllowedException();
		}
	}

	/**
	 * 게시글의 수정 가능 여부를 확인한다.
	 *
	 * @param post 수정 대상 게시글
	 * @throws FileConversionLockedException 파일 변환 요청 게시글에 첨부파일이 있는 경우
	 */
	private void ensurePostIsEditable(BoardPost post) {
		if (post.getMode() == BoardPostMode.FILE_CONVERSION_REQUEST && !findAttachments(post.getId()).isEmpty()) {
			throw new FileConversionLockedException(post.getId());
		}
	}

	/**
	 * 작성자 본인 또는 ADMIN만 게시글 수정/삭제 가능.
	 * authorUserId가 null인 레거시 글은 ADMIN만 관리 가능.
	 */
	private void ensureCanManagePost(Long actorUserId, BoardPost post) {
		AuthenticatedUser actor = requireExistingUser(actorUserId);
		if (actor.role() == UserRole.ADMIN) {
			return;
		}
		if (!isOwner(actorUserId, post.getAuthorUserId())) {
			throw new ForbiddenException("작성자 본인 또는 관리자만 수정·삭제할 수 있습니다.");
		}
	}

	/**
	 * 작성자 본인 또는 ADMIN만 댓글 수정/삭제 가능.
	 * AI 답변은 앞선 ensureReplyIsEditable에서 차단된다.
	 * authorUserId가 null인 레거시 일반 댓글은 ADMIN만 관리 가능.
	 */
	private void ensureCanManageReply(Long actorUserId, BoardReply reply) {
		AuthenticatedUser actor = requireExistingUser(actorUserId);
		if (actor.role() == UserRole.ADMIN) {
			return;
		}
		if (!isOwner(actorUserId, reply.getAuthorUserId())) {
			throw new ForbiddenException("작성자 본인 또는 관리자만 수정·삭제할 수 있습니다.");
		}
	}

	/**
	 * 사용자가 게시글 작성자인지 확인한다.
	 *
	 * @param actorUserId 작업을 수행하는 사용자 ID
	 * @param authorUserId 게시글 작성자 ID
	 * @return 작성자 ID가 존재하고 두 사용자 ID가 같은 경우 {@code true}
	 */
	private boolean isOwner(Long actorUserId, Long authorUserId) {
		return authorUserId != null
			&& Objects.equals(authorUserId, actorUserId);
	}

	/**
	 * 사용자 ID에 해당하는 기존 사용자를 조회한다.
	 *
	 * @param userId 조회할 사용자 ID
	 * @return 인증된 사용자 정보
	 */
	private AuthenticatedUser requireExistingUser(Long userId) {
		return identityAccess.requireUser(userId);
	}

	/**
	 * 게시글과 첨부파일을 상세 응답으로 변환한다.
	 *
	 * @param post 변환할 게시글
	 * @return 게시글 상세 응답
	 */
	private BoardPostDetailResponse toDetailResponse(BoardPost post) {
		return boardMapper.toDetailResponse(post, findAttachments(post.getId()));
	}

	/**
	 * 게시글의 첨부파일을 생성 순서로 조회한다.
	 *
	 * @param postId 첨부파일을 조회할 게시글 ID
	 * @return 게시글에 연결된 첨부파일 목록
	 */
	private List<BoardAttachment> findAttachments(Long postId) {
		return boardAttachmentRepository.findByPost_IdOrderByCreatedAtAscIdAsc(postId);
	}

	/**
	 * 검색어를 저장소 조회용 소문자 포함 패턴으로 변환한다.
	 *
	 * @param query 원본 검색어
	 * @return 검색 패턴 또는 검색어가 없을 때 {@code null}
	 */
	private String toKeywordPattern(String query) {
		if (!StringUtils.hasText(query)) {
			return null;
		}

		return "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
	}

	/**
	 * 게시글 본문이 수동 작성 가능한 모드인지 확인한 뒤 디코딩한다.
	 *
	 * @param mode 게시글 모드
	 * @param bodyBase64 Base64로 인코딩된 본문
	 * @return 디코딩된 게시글 본문
	 */
	private String resolvePostBody(BoardPostMode mode, String bodyBase64) {
		ensureManualPostMode(mode);
		return boardContentCodec.decodeOptionalBody(bodyBase64);
	}

	/**
	 * 수동 게시글 생성·수정에 허용되는 모드인지 확인한다.
	 *
	 * @param mode 확인할 게시글 모드
	 * @throws InvalidFileConversionRequestException 파일 변환 요청 모드인 경우
	 */
	private void ensureManualPostMode(BoardPostMode mode) {
		if (mode == BoardPostMode.FILE_CONVERSION_REQUEST) {
			throw new InvalidFileConversionRequestException(
				"manual file conversion request posts are not supported; use the upload session API"
			);
		}
	}

	/**
	 * 게시글의 첨부파일 추가·삭제 요청을 현재 상태에 반영한다.
	 *
	 * @param post 첨부파일을 동기화할 게시글
	 * @param uploads 새로 업로드할 파일 목록
	 * @param removeAttachmentIds 삭제할 첨부파일 ID 목록
	 * @param now 새 첨부파일 메타데이터에 사용할 시각
	 */
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

		// Every new file is registered before its DB row is saved, including commit-time failures.
		for (MultipartFile upload : newUploads) {
			AttachmentStorageService.StoredAttachment stored = attachmentStorageService.store(upload);
			attachmentFileLifecycle.trackCreated(stored.storagePath());
			boardAttachmentRepository.save(new BoardAttachment(
				post, stored.originalFilename(), stored.storedFilename(), stored.storagePath(),
				stored.contentType(), stored.size(), now
			));
		}

		// 기존 metadata 삭제와 파일 삭제 예약은 같은 트랜잭션에 참여한다.
		toRemove.forEach(this::deleteAttachment);
	}

	/**
	 * 첨부파일 메타데이터를 삭제하고 커밋 후 실파일 삭제를 예약한다.
	 *
	 * @param attachment 삭제할 첨부파일 메타데이터
	 */
	private void deleteAttachment(BoardAttachment attachment) {
		attachmentFileLifecycle.deleteAfterCommit(attachment.getStoragePath());
		boardAttachmentRepository.delete(attachment);
		boardAttachmentRepository.flush();
	}

	/**
	 * 업로드 항목에 저장할 파일명이 있는지 확인한다.
	 *
	 * @param attachment 확인할 멀티파트 파일
	 * @return 파일과 원본 파일명이 모두 유효하면 {@code true}
	 */
	private boolean hasAttachmentUpload(MultipartFile attachment) {
		return attachment != null && StringUtils.hasText(attachment.getOriginalFilename());
	}
}
