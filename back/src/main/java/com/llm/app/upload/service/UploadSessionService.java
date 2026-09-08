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

	@Transactional(readOnly = true)
	public UploadSessionStatusSnapshot getSession(Long userId, UUID sessionId) {
		UploadSession session = findActiveSession(sessionId, userId);
		return toStatusSnapshot(session, uploadedChunkNumbers(sessionId));
	}

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

	@Scheduled(fixedDelayString = "${app.upload-sessions.cleanup-fixed-delay-ms:3600000}")
	public void cleanupExpiredSessions() {
		Instant now = Instant.now();
		for (UploadSession session : uploadSessionRepository.findByExpiresAtBefore(now)) {
			registerSessionDirectoryDeletion(session.getId());
			deleteSessionRows(session);
		}
	}

	private String requireUsername(Long userId) {
		return requireUser(userId).username();
	}

	private AuthenticatedUser requireUser(Long userId) {
		return identityAccess.requireUser(userId);
	}

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

	private void validateDecodedRequest(CreateUploadSessionRequest request) {
		// The controller @Valid-ates only the encrypted envelope; the DECODED request is built by the wire
		// codec and never bean-validated. Enforce its @NotBlank/@Size/@Pattern/@Positive here so a hostile
		// A1..A5 payload (blank/over-long archiveName, bad sha, non-positive sizes) is a clean 400.
		Set<ConstraintViolation<CreateUploadSessionRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new InvalidUploadSessionRequestException(violations.iterator().next().getMessage());
		}
	}

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

	private MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 digest is not available", exception);
		}
	}

	private long expectedEncodedChunkSize(UploadSession session, int chunkNumber) {
		if (chunkNumber < session.getTotalChunks()) {
			return session.getChunkSizeBase64Chars();
		}
		long remainder = encodedLength(session.getFileSizeBytes()) % session.getChunkSizeBase64Chars();
		return remainder == 0 ? session.getChunkSizeBase64Chars() : remainder;
	}

	private long expectedDecodedChunkSize(UploadSession session, int chunkNumber) {
		long fullChunkDecodedSize = decodedChunkSize(session.getChunkSizeBase64Chars());
		if (chunkNumber < session.getTotalChunks()) {
			return fullChunkDecodedSize;
		}
		long consumedBytes = fullChunkDecodedSize * (session.getTotalChunks() - 1L);
		return session.getFileSizeBytes() - consumedBytes;
	}

	private List<Integer> uploadedChunkNumbers(UUID sessionId) {
		return uploadSessionPartRepository.findBySession_IdOrderByChunkNumberAsc(sessionId).stream()
			.map(UploadSessionPart::getChunkNumber)
			.toList();
	}

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

	private String buildTitle(String archiveName) {
		String suffix = "] 업로드 완료";
		int limit = 200 - 1 - suffix.length();
		int end = Math.min(limit, archiveName.length());
		if (end > 0 && Character.isHighSurrogate(archiveName.charAt(end - 1))) end--;
		return "[" + archiveName.substring(0, end) + suffix;
	}

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

	private long divideAndRoundUp(long value, long unit) {
		return (value + unit - 1) / unit;
	}

	private long encodedLength(long fileSizeBytes) {
		return divideAndRoundUp(fileSizeBytes, 3L) * 4L;
	}

	private long decodedChunkSize(long chunkSizeBase64Chars) {
		return (chunkSizeBase64Chars / 4L) * 3L;
	}

	private byte[] decodeBase64Chunk(String chunkDataBase64) {
		try {
			return Base64.getDecoder().decode(chunkDataBase64);
		} catch (IllegalArgumentException exception) {
			throw new InvalidUploadSessionRequestException("chunkDataBase64 must be valid base64");
		}
	}

	private void registerSessionDirectoryDeletion(UUID sessionId) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				uploadSessionStorageService.deleteSessionDirectory(sessionId);
			}
		});
	}

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

	private void markFailedSafely(UUID sessionId) {
		try {
			uploadSessionFailureService.markFailed(sessionId, Instant.now());
		} catch (RuntimeException markFailedFailure) {
			// session may have been concurrently removed / DB hiccup; never mask the original failure
		}
	}

	private void deleteSessionRows(UploadSession session) {
		uploadSessionPartRepository.deleteBySession(session);
		uploadSessionRepository.delete(session);
	}

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
