package com.llm.app.upload.service;

import com.llm.app.auth.api.AuthenticatedUser;
import com.llm.app.auth.api.IdentityAccess;
import com.llm.app.auth.api.InvalidCredentialsException;
import com.llm.app.board.api.upload.GeneratedAttachmentPolicy;
import com.llm.app.board.api.upload.UploadedPostCreationCommand;
import com.llm.app.board.api.upload.UploadedPostCreationResult;
import com.llm.app.board.api.upload.UploadedPostCreator;
import com.llm.app.upload.dto.CreateUploadSessionRequest;
import com.llm.app.upload.exception.InvalidUploadSessionRequestException;
import com.llm.app.upload.exception.UploadSessionChunkTooLargeException;
import com.llm.app.upload.exception.UploadSessionNotFoundException;
import com.llm.app.upload.exception.UploadSessionStorageException;
import com.llm.app.upload.exception.UploadSessionStateException;
import com.llm.app.upload.model.UploadSession;
import com.llm.app.upload.model.UploadSessionPart;
import com.llm.app.upload.model.UploadSessionStatus;
import com.llm.app.upload.repository.UploadSessionPartRepository;
import com.llm.app.upload.repository.UploadSessionRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@Transactional
public class UploadSessionService {
	private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);
	private static final HexFormat HEX_FORMAT = HexFormat.of();

	private final UploadSessionRepository uploadSessionRepository;
	private final UploadSessionPartRepository uploadSessionPartRepository;
	private final UploadSessionStorageService uploadSessionStorageService;
	private final GeneratedAttachmentPolicy generatedAttachmentPolicy;
	private final UploadedPostCreator uploadedPostCreator;
	private final UploadSessionFailureService uploadSessionFailureService;
	private final Validator validator;
	private final long expirationMs;
	private final IdentityAccess identityAccess;

	/**
	 * 업로드 세션 처리에 필요한 저장소와 정책 의존성을 주입한다.
	 *
	 * @param uploadSessionRepository 업로드 세션 저장소
	 * @param uploadSessionPartRepository 업로드 청크 메타데이터 저장소
	 * @param uploadSessionStorageService 청크 파일 저장 서비스
	 * @param generatedAttachmentPolicy 생성 첨부파일 정책
	 * @param uploadedPostCreator 업로드 완료 게시글 생성기
	 * @param uploadSessionFailureService 실패한 세션 상태 기록 서비스
	 * @param validator 디코딩된 요청 검증기
	 * @param identityAccess 인증 사용자 조회기
	 * @param expirationMs 세션 만료까지의 시간(밀리초)
	 */
	public UploadSessionService(
		UploadSessionRepository uploadSessionRepository,
		UploadSessionPartRepository uploadSessionPartRepository,
		UploadSessionStorageService uploadSessionStorageService,
		GeneratedAttachmentPolicy generatedAttachmentPolicy,
		UploadedPostCreator uploadedPostCreator,
		UploadSessionFailureService uploadSessionFailureService,
		Validator validator,
		IdentityAccess identityAccess,
		@org.springframework.beans.factory.annotation.Value("${app.upload-sessions.expiration-ms:86400000}") long expirationMs
	) {
		this.uploadSessionRepository = uploadSessionRepository;
		this.uploadSessionPartRepository = uploadSessionPartRepository;
		this.uploadSessionStorageService = uploadSessionStorageService;
		this.generatedAttachmentPolicy = generatedAttachmentPolicy;
		this.uploadedPostCreator = uploadedPostCreator;
		this.uploadSessionFailureService = uploadSessionFailureService;
		this.validator = validator;
		this.identityAccess = identityAccess;
		this.expirationMs = expirationMs;
	}

	/**
	 * 검증된 요청으로 새 업로드 세션을 생성한다.
	 *
	 * @param userId 세션을 생성하는 사용자 ID
	 * @param request 업로드 파일과 청크 구성 요청
	 * @return 생성된 세션의 상태 정보
	 */
	public UploadSessionStatusSnapshot createSession(Long userId, CreateUploadSessionRequest request) {
		String username = requireUsername(userId);
		validateDecodedRequest(request);
		validateCreateRequest(request);

		Instant now = Instant.now();
		String archiveName = normalizeArchiveName(request.archiveName());
		UploadSession session = uploadSessionRepository.save(new UploadSession(
			UUID.randomUUID(),
			archiveName,
			request.fileSizeBytes(),
			request.chunkSizeBase64Chars(),
			request.totalChunks(),
			request.fileSha256(),
			UploadSessionStatus.PENDING,
			username,
			now,
			now,
			now.plusMillis(expirationMs),
			userId
		));
		return toStatusSnapshot(session, List.of());
	}

	/**
	 * 사용자가 소유한 활성 업로드 세션의 현재 상태를 조회한다.
	 *
	 * @param userId 세션 소유자 ID
	 * @param sessionId 조회할 세션 ID
	 * @return 업로드된 청크 목록을 포함한 세션 상태
	 */
	@Transactional(readOnly = true)
	public UploadSessionStatusSnapshot getSession(Long userId, UUID sessionId) {
		UploadSession session = findActiveSession(sessionId, userId);
		return toStatusSnapshot(session, uploadedChunkNumbers(sessionId));
	}

	/**
	 * Base64로 인코딩된 청크를 검증하고 저장한다.
	 *
	 * <p>이미 저장된 청크를 다시 받으면 파일을 덮어쓰지 않고 세션 만료 시간만 갱신한다.</p>
	 *
	 * @param userId 세션 소유자 ID
	 * @param sessionId 대상 업로드 세션 ID
	 * @param chunkNumber 저장할 청크 번호
	 * @param chunkDataBase64 청크의 Base64 문자열
	 * @return 업로드 후 세션 상태
	 */
	public UploadSessionStatusSnapshot uploadChunk(Long userId, UUID sessionId, int chunkNumber, String chunkDataBase64) {
		UploadSession session = findActiveSession(sessionId, userId);
		validateChunkRequest(session, chunkNumber, chunkDataBase64);

		Instant now = Instant.now();
		if (uploadSessionPartRepository.findBySession_IdAndChunkNumber(sessionId, chunkNumber).isPresent()) {
			session.refreshExpiry(now, expirationMs);
			return toStatusSnapshot(session, uploadedChunkNumbers(sessionId));
		}

		byte[] chunkBytes = decodeBase64Chunk(chunkDataBase64);
		long expectedDecodedSize = expectedDecodedChunkSize(session, chunkNumber);
		if (chunkBytes.length != expectedDecodedSize) {
			throw new InvalidUploadSessionRequestException(
				"chunk size mismatch for chunkNumber %d: expected %d decoded bytes".formatted(
					chunkNumber,
					expectedDecodedSize
				)
			);
		}
		var storedChunk = uploadSessionStorageService.store(sessionId, chunkNumber, session.getArchiveName(), chunkBytes);
		uploadSessionPartRepository.save(new UploadSessionPart(
			session,
			chunkNumber,
			storedChunk.originalFilename(),
			storedChunk.storedFilename(),
			storedChunk.storagePath(),
			storedChunk.size(),
			now
		));
		session.refreshExpiry(now, expirationMs);

		return toStatusSnapshot(session, uploadedChunkNumbers(sessionId));
	}

	/**
	 * 모든 청크를 하나의 파일로 조립하고 업로드 결과 게시글을 생성한다.
	 *
	 * <p>조립 파일의 크기와 SHA-256을 검증한 뒤 게시글을 생성하며, 성공하면 세션 메타데이터를 삭제한다.</p>
	 *
	 * @param userId 세션 소유자 ID
	 * @param sessionId 완료할 업로드 세션 ID
	 * @return 생성된 게시글 정보
	 */
	public UploadedPostCreationResult finalizeSession(Long userId, UUID sessionId) {
		UploadSession session = findActiveSession(sessionId, userId);
		List<UploadSessionPart> chunks = uploadSessionPartRepository.findBySession_IdOrderByChunkNumberAsc(sessionId);
		validateChunks(session, chunks);

		Instant now = Instant.now();
		session.markFinalizing(now);
		Path assembledPath = null;

		try {
			assembledPath = uploadSessionStorageService.createAssembledTarget(sessionId, session.getArchiveName());
			registerFinalizeCleanup(session.getId(), assembledPath);
			uploadSessionStorageService.concatenate(
				assembledPath,
				chunks.stream().map(chunk -> uploadSessionStorageService.resolve(chunk.getStoragePath())).toList()
			);

			validateAssembledFile(session, assembledPath);

			UploadedPostCreationResult result = uploadedPostCreator.create(new UploadedPostCreationCommand(
				buildTitle(session.getArchiveName()),
				buildBody(
					session.getArchiveName(),
					session.getFileSizeBytes(),
					session.getTotalChunks(),
					session.getFileSha256()
				),
				assembledPath,
				session.getArchiveName(),
				"application/zip",
				userId,
				requireUsername(userId),
				now
			));

			session.markCompleted(now);
			deleteSessionRows(session);
			return result;
		} catch (RuntimeException exception) {
			registerFailureMarking(session.getId());
			throw exception;
		}
	}

	/**
	 * 만료된 업로드 세션과 관련 청크를 주기적으로 정리한다.
	 *
	 * <p>데이터베이스 행을 삭제한 뒤 커밋 시점에 세션 디렉터리도 삭제한다.</p>
	 */
	@Scheduled(fixedDelayString = "${app.upload-sessions.cleanup-fixed-delay-ms:3600000}")
	public void cleanupExpiredSessions() {
		Instant now = Instant.now();
		for (UploadSession session : uploadSessionRepository.findByExpiresAtBefore(now)) {
			registerSessionDirectoryDeletion(session.getId());
			deleteSessionRows(session);
		}
	}

	/**
	 * 사용자 ID로 인증된 사용자의 username을 조회한다.
	 *
	 * @param userId 인증 사용자 ID
	 * @return 사용자 username
	 */
	private String requireUsername(Long userId) {
		return requireUser(userId).username();
	}

	/**
	 * 사용자 ID에 해당하는 인증 사용자 정보를 조회한다.
	 *
	 * @param userId 인증 사용자 ID
	 * @return 인증된 사용자 정보
	 */
	private AuthenticatedUser requireUser(Long userId) {
		return identityAccess.requireUser(userId);
	}

	/**
	 * 사용자가 소유하면서 아직 처리 가능한 업로드 세션을 조회한다.
	 *
	 * @param sessionId 조회할 세션 ID
	 * @param userId 접근을 시도하는 사용자 ID
	 * @return 활성 업로드 세션
	 */
	private UploadSession findActiveSession(UUID sessionId, Long userId) {
		requireUser(userId);
		UploadSession session = uploadSessionRepository.findById(sessionId)
			.orElseThrow(() -> UploadSessionNotFoundException.session(sessionId));
		if (session.getCreatedByUserId() == null || !Objects.equals(session.getCreatedByUserId(), userId)) {
			throw new InvalidCredentialsException("upload session access denied");
		}
		if (session.isExpired(Instant.now())) {
			throw new UploadSessionStateException("upload session has expired");
		}
		if (session.getStatus() == UploadSessionStatus.COMPLETED) {
			throw new UploadSessionStateException("upload session is already completed");
		}
		if (session.getStatus() == UploadSessionStatus.FINALIZING) {
			throw new UploadSessionStateException("upload session is currently finalizing");
		}
		return session;
	}

	/**
	 * 암호 해독된 세션 생성 요청의 Bean Validation 제약을 검증한다.
	 *
	 * @param request 검증할 세션 생성 요청
	 * @throws InvalidUploadSessionRequestException 요청 제약을 위반한 경우
	 */
	private void validateDecodedRequest(CreateUploadSessionRequest request) {
		// The controller @Valid-ates only the encrypted envelope; the DECODED request is built by the wire
		// codec and never bean-validated. Enforce its @NotBlank/@Size/@Pattern/@Positive here so a hostile
		// A1..A5 payload (blank/over-long archiveName, bad sha, non-positive sizes) is a clean 400.
		Set<ConstraintViolation<CreateUploadSessionRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new InvalidUploadSessionRequestException(violations.iterator().next().getMessage());
		}
	}

	/**
	 * 세션 생성 요청의 Base64 청크 구성과 파일 크기 제한을 검증한다.
	 *
	 * @param request 검증할 세션 생성 요청
	 * @throws InvalidUploadSessionRequestException 청크 구성이나 크기 제한이 잘못된 경우
	 */
	private void validateCreateRequest(CreateUploadSessionRequest request) {
		if (request.chunkSizeBase64Chars() % 4 != 0) {
			throw new InvalidUploadSessionRequestException("chunkSizeBase64Chars must be a multiple of 4");
		}
		long expectedTotalChunks = divideAndRoundUp(encodedLength(request.fileSizeBytes()), request.chunkSizeBase64Chars());
		if (request.totalChunks() != expectedTotalChunks) {
			throw new InvalidUploadSessionRequestException(
				"totalChunks does not match fileSizeBytes and chunkSizeBase64Chars"
			);
		}
		long fullChunkDecodedSize = decodedChunkSize(request.chunkSizeBase64Chars());
		long lastChunkDecodedSize = request.totalChunks() == 1
			? request.fileSizeBytes()
			: request.fileSizeBytes() - fullChunkDecodedSize * (request.totalChunks() - 1L);
		long maxActualChunkBytes = request.totalChunks() == 1
			? lastChunkDecodedSize
			: Math.max(fullChunkDecodedSize, lastChunkDecodedSize);
		if (maxActualChunkBytes > uploadSessionStorageService.getMaxDecodedChunkSizeBytes()) {
			throw new InvalidUploadSessionRequestException("decoded chunk size exceeds the upload session size limit");
		}
		if (request.fileSizeBytes() > generatedAttachmentPolicy.getMaxGeneratedFileSizeBytes()) {
			throw new InvalidUploadSessionRequestException("fileSizeBytes exceeds the generated attachment size limit");
		}
	}

	/**
	 * 개별 청크의 번호, Base64 길이와 디코딩 후 크기를 검증한다.
	 *
	 * @param session 대상 업로드 세션
	 * @param chunkNumber 검증할 청크 번호
	 * @param chunkDataBase64 검증할 Base64 청크 데이터
	 * @throws InvalidUploadSessionRequestException 청크 형식이나 크기가 잘못된 경우
	 */
	private void validateChunkRequest(UploadSession session, int chunkNumber, String chunkDataBase64) {
		if (chunkNumber < 1 || chunkNumber > session.getTotalChunks()) {
			throw new InvalidUploadSessionRequestException("chunkNumber must be between 1 and totalChunks");
		}
		if (!StringUtils.hasText(chunkDataBase64)) {
			throw new InvalidUploadSessionRequestException("chunkDataBase64 is required");
		}
		long expectedEncodedSize = expectedEncodedChunkSize(session, chunkNumber);
		if (chunkDataBase64.length() != expectedEncodedSize) {
			throw new InvalidUploadSessionRequestException(
				"chunk size mismatch for chunkNumber %d: expected %d base64 chars".formatted(
					chunkNumber,
					expectedEncodedSize
				)
			);
		}
		if (expectedDecodedChunkSize(session, chunkNumber) > uploadSessionStorageService.getMaxDecodedChunkSizeBytes()) {
			throw new InvalidUploadSessionRequestException("decoded chunk size exceeds the upload session size limit");
		}
	}

	/**
	 * 업로드된 청크가 모두 존재하고 순서와 크기가 세션 구성과 일치하는지 검증한다.
	 *
	 * @param session 검증 대상 업로드 세션
	 * @param chunks 세션에 저장된 청크 목록
	 * @throws InvalidUploadSessionRequestException 청크가 누락되었거나 구성이 일치하지 않는 경우
	 */
	private void validateChunks(UploadSession session, List<UploadSessionPart> chunks) {
		if (chunks.size() != session.getTotalChunks()) {
			throw new InvalidUploadSessionRequestException("all chunks must be uploaded before finalization");
		}
		long totalSize = 0L;
		for (int index = 0; index < chunks.size(); index++) {
			UploadSessionPart chunk = chunks.get(index);
			int expectedChunkNumber = index + 1;
			if (chunk.getChunkNumber() != expectedChunkNumber) {
				throw new InvalidUploadSessionRequestException("uploaded chunks must be contiguous from 1 to totalChunks");
			}
			long expectedSize = expectedDecodedChunkSize(session, expectedChunkNumber);
			if (chunk.getSize() != expectedSize) {
				throw new InvalidUploadSessionRequestException("stored chunk size does not match the expected session layout");
			}
			totalSize += chunk.getSize();
		}
		if (totalSize != session.getFileSizeBytes()) {
			throw new InvalidUploadSessionRequestException("uploaded chunks do not add up to fileSizeBytes");
		}
	}

	/**
	 * 조립된 파일의 크기와 SHA-256 해시가 세션에 기록된 값과 일치하는지 검증한다.
	 *
	 * @param session 검증 대상 업로드 세션
	 * @param assembledPath 조립된 파일 경로
	 * @throws InvalidUploadSessionRequestException 파일 검증에 실패한 경우
	 */
	private void validateAssembledFile(UploadSession session, Path assembledPath) {
		long actualSize;
		try {
			actualSize = Files.size(assembledPath);
		} catch (IOException exception) {
			throw new InvalidUploadSessionRequestException("failed to read assembled file size");
		}
		if (actualSize != session.getFileSizeBytes()) {
			throw new InvalidUploadSessionRequestException("assembled file size does not match fileSizeBytes");
		}

		String actualSha256 = computeSha256(assembledPath);
		if (!session.getFileSha256().equals(actualSha256)) {
			throw new InvalidUploadSessionRequestException("assembled file sha256 does not match fileSha256");
		}
	}

	/**
	 * 파일을 스트리밍으로 읽어 SHA-256 해시 문자열을 계산한다.
	 *
	 * @param path 해시를 계산할 파일 경로
	 * @return 소문자 16진수로 표현한 SHA-256 해시
	 * @throws InvalidUploadSessionRequestException 파일을 읽을 수 없는 경우
	 */
	private String computeSha256(Path path) {
		MessageDigest messageDigest = sha256Digest();
		try (InputStream inputStream = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				messageDigest.update(buffer, 0, bytesRead);
			}
		} catch (IOException exception) {
			throw new InvalidUploadSessionRequestException("failed to hash assembled file");
		}
		return HEX_FORMAT.formatHex(messageDigest.digest());
	}

	/**
	 * SHA-256 알고리즘을 사용하는 메시지 다이제스트를 생성한다.
	 *
	 * @return SHA-256 메시지 다이제스트
	 * @throws IllegalStateException 실행 환경에서 SHA-256을 지원하지 않는 경우
	 */
	private MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 digest is not available", exception);
		}
	}

	/**
	 * 지정한 청크의 예상 Base64 문자열 길이를 계산한다.
	 *
	 * @param session 대상 업로드 세션
	 * @param chunkNumber 계산할 청크 번호
	 * @return 예상 Base64 문자 수
	 */
	private long expectedEncodedChunkSize(UploadSession session, int chunkNumber) {
		if (chunkNumber < session.getTotalChunks()) {
			return session.getChunkSizeBase64Chars();
		}
		long remainder = encodedLength(session.getFileSizeBytes()) % session.getChunkSizeBase64Chars();
		return remainder == 0 ? session.getChunkSizeBase64Chars() : remainder;
	}

	/**
	 * 지정한 청크의 디코딩 후 예상 바이트 수를 계산한다.
	 *
	 * @param session 대상 업로드 세션
	 * @param chunkNumber 계산할 청크 번호
	 * @return 예상 디코딩 바이트 수
	 */
	private long expectedDecodedChunkSize(UploadSession session, int chunkNumber) {
		long fullChunkDecodedSize = decodedChunkSize(session.getChunkSizeBase64Chars());
		if (chunkNumber < session.getTotalChunks()) {
			return fullChunkDecodedSize;
		}
		long consumedBytes = fullChunkDecodedSize * (session.getTotalChunks() - 1L);
		return session.getFileSizeBytes() - consumedBytes;
	}

	/**
	 * 세션에 저장된 청크 번호를 오름차순으로 조회한다.
	 *
	 * @param sessionId 대상 업로드 세션 ID
	 * @return 저장된 청크 번호 목록
	 */
	private List<Integer> uploadedChunkNumbers(UUID sessionId) {
		return uploadSessionPartRepository.findBySession_IdOrderByChunkNumberAsc(sessionId).stream()
			.map(UploadSessionPart::getChunkNumber)
			.toList();
	}

	/**
	 * 세션 엔티티와 업로드 청크 목록을 API 응답용 상태 정보로 변환한다.
	 *
	 * @param session 상태를 변환할 업로드 세션
	 * @param uploadedChunks 업로드된 청크 번호 목록
	 * @return 세션 상태 스냅샷
	 */
	private UploadSessionStatusSnapshot toStatusSnapshot(UploadSession session, List<Integer> uploadedChunks) {
		return new UploadSessionStatusSnapshot(
			session.getId(),
			session.getArchiveName(),
			session.getFileSizeBytes(),
			session.getChunkSizeBase64Chars(),
			session.getTotalChunks(),
			uploadedChunks,
			uploadedChunks.size() == session.getTotalChunks(),
			session.getExpiresAt()
		);
	}

	/**
	 * 아카이브 파일명에서 경로를 제거하고 유효한 파일명만 반환한다.
	 *
	 * @param archiveName 원본 아카이브 파일명
	 * @return 경로가 제거된 파일명
	 * @throws InvalidUploadSessionRequestException 파일명이 비어 있거나 유효하지 않은 경우
	 */
	private String normalizeArchiveName(String archiveName) {
		if (!StringUtils.hasText(archiveName)) {
			throw new InvalidUploadSessionRequestException("archiveName is required");
		}
		Path fileName;
		try {
			fileName = Path.of(archiveName).getFileName();
		} catch (RuntimeException exception) {
			throw new InvalidUploadSessionRequestException("archiveName is invalid");
		}
		if (fileName == null || !StringUtils.hasText(fileName.toString())) {
			throw new InvalidUploadSessionRequestException("archiveName is invalid");
		}
		return fileName.toString();
	}

	/**
	 * 업로드 완료 게시글에 사용할 제목을 생성하고 길이를 제한한다.
	 *
	 * @param archiveName 원본 아카이브 파일명
	 * @return 업로드 완료 게시글 제목
	 */
	private String buildTitle(String archiveName) {
		String suffix = "] 업로드 완료";
		int limit = 200 - 1 - suffix.length();
		int end = Math.min(limit, archiveName.length());
		if (end > 0 && Character.isHighSurrogate(archiveName.charAt(end - 1))) end--;
		return "[" + archiveName.substring(0, end) + suffix;
	}

	/**
	 * 업로드 파일의 기본 메타데이터를 게시글 본문으로 생성한다.
	 *
	 * @param archiveName 원본 아카이브 파일명
	 * @param fileSizeBytes 원본 파일 크기
	 * @param totalChunks 전체 청크 수
	 * @param fileSha256 원본 파일 SHA-256 해시
	 * @return 자동 생성 게시글 본문
	 */
	private String buildBody(String archiveName, long fileSizeBytes, int totalChunks, String fileSha256) {
		return """
			자동 업로드 생성 게시글입니다.
			원본 파일명: %s
			전체 크기(bytes): %d
			청크 수: %d
			SHA-256: %s
			SHA-256 검증: 성공
			""".formatted(archiveName, fileSizeBytes, totalChunks, fileSha256);
	}

	/**
	 * 나눗셈 결과를 올림하여 계산한다.
	 *
	 * @param value 나눌 값
	 * @param unit 나누는 단위
	 * @return 올림한 나눗셈 결과
	 */
	private long divideAndRoundUp(long value, long unit) {
		return (value + unit - 1) / unit;
	}

	/**
	 * 원본 바이트 수에 필요한 Base64 인코딩 문자 수를 계산한다.
	 *
	 * @param fileSizeBytes 원본 파일 바이트 수
	 * @return 필요한 Base64 문자 수
	 */
	private long encodedLength(long fileSizeBytes) {
		return divideAndRoundUp(fileSizeBytes, 3L) * 4L;
	}

	/**
	 * Base64 문자 수에 대응하는 디코딩 후 바이트 수를 계산한다.
	 *
	 * @param chunkSizeBase64Chars Base64 문자 수
	 * @return 디코딩 후 바이트 수
	 */
	private long decodedChunkSize(long chunkSizeBase64Chars) {
		return (chunkSizeBase64Chars / 4L) * 3L;
	}

	/**
	 * Base64 청크를 바이트 배열로 디코딩한다.
	 *
	 * @param chunkDataBase64 디코딩할 Base64 청크
	 * @return 디코딩된 청크 바이트
	 * @throws InvalidUploadSessionRequestException 유효하지 않은 Base64인 경우
	 */
	private byte[] decodeBase64Chunk(String chunkDataBase64) {
		try {
			return Base64.getDecoder().decode(chunkDataBase64);
		} catch (IllegalArgumentException exception) {
			throw new InvalidUploadSessionRequestException("chunkDataBase64 must be valid base64");
		}
	}

	/**
	 * 트랜잭션 커밋 후 세션 디렉터리를 삭제하도록 동기화를 등록한다.
	 *
	 * @param sessionId 삭제할 업로드 세션 ID
	 */
	private void registerSessionDirectoryDeletion(UUID sessionId) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				uploadSessionStorageService.deleteSessionDirectory(sessionId);
			}
		});
	}

	/**
	 * 최종화 트랜잭션 결과에 따라 임시 파일 정리를 예약한다.
	 *
	 * @param sessionId 정리할 업로드 세션 ID
	 * @param assembledPath 트랜잭션 롤백 시 삭제할 조립 파일 경로
	 */
	private void registerFinalizeCleanup(UUID sessionId, Path assembledPath) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				uploadSessionStorageService.deleteSessionDirectory(sessionId);
			}

			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_ROLLED_BACK) {
					deleteAssembledFileIfExists(assembledPath);
				} else if (status == STATUS_UNKNOWN) {
					log.error("Retaining assembled upload file after unknown transaction outcome: {}", assembledPath);
				}
			}
		});
	}

	/**
	 * 최종화 실패 시 트랜잭션 종료 후 세션을 실패 상태로 표시하도록 예약한다.
	 *
	 * @param sessionId 실패한 업로드 세션 ID
	 */
	private void registerFailureMarking(UUID sessionId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			markFailedSafely(sessionId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_ROLLED_BACK) {
					markFailedSafely(sessionId);
				} else if (status == STATUS_UNKNOWN) {
					log.error("Unable to mark upload session failed after unknown transaction outcome: {}", sessionId);
				}
			}
		});
	}

	/**
	 * 원래 예외를 가리지 않도록 업로드 세션 실패 표시를 best-effort로 수행한다.
	 *
	 * @param sessionId 실패 처리할 업로드 세션 ID
	 */
	private void markFailedSafely(UUID sessionId) {
		try {
			uploadSessionFailureService.markFailed(sessionId, Instant.now());
		} catch (RuntimeException markFailedFailure) {
			// session may have been concurrently removed / DB hiccup; never mask the original failure
		}
	}

	/**
	 * 업로드 세션과 그에 속한 청크 메타데이터를 삭제한다.
	 *
	 * @param session 삭제할 업로드 세션
	 */
	private void deleteSessionRows(UploadSession session) {
		uploadSessionPartRepository.deleteBySession(session);
		uploadSessionRepository.delete(session);
	}

	/**
	 * 조립 파일이 존재하면 삭제하고, 정리 실패는 원래 처리 결과에 영향을 주지 않도록 무시한다.
	 *
	 * @param assembledPath 삭제할 조립 파일 경로
	 */
	private void deleteAssembledFileIfExists(Path assembledPath) {
		if (assembledPath == null) {
			return;
		}
		try {
			Files.deleteIfExists(assembledPath);
		} catch (IOException exception) {
			// Best-effort cleanup for failed finalize attempts.
		}
	}
}
