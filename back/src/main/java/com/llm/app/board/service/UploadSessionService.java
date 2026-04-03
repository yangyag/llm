package com.llm.app.board.service;

import com.llm.app.auth.InvalidCredentialsException;
import com.llm.app.board.dto.BoardPostDetailResponse;
import com.llm.app.board.dto.CreateUploadSessionRequest;
import com.llm.app.board.exception.InvalidUploadSessionRequestException;
import com.llm.app.board.exception.UploadSessionNotFoundException;
import com.llm.app.board.exception.UploadSessionStateException;
import com.llm.app.board.model.BoardAttachment;
import com.llm.app.board.model.BoardPost;
import com.llm.app.board.model.BoardPostMode;
import com.llm.app.board.model.UploadSession;
import com.llm.app.board.model.UploadSessionPart;
import com.llm.app.board.model.UploadSessionStatus;
import com.llm.app.board.repository.BoardAttachmentRepository;
import com.llm.app.board.repository.BoardPostRepository;
import com.llm.app.board.repository.UploadSessionPartRepository;
import com.llm.app.board.repository.UploadSessionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@Transactional
public class UploadSessionService {
	private static final HexFormat HEX_FORMAT = HexFormat.of();

	private final UploadSessionRepository uploadSessionRepository;
	private final UploadSessionPartRepository uploadSessionPartRepository;
	private final UploadSessionStorageService uploadSessionStorageService;
	private final AttachmentStorageService attachmentStorageService;
	private final BoardPostRepository boardPostRepository;
	private final BoardAttachmentRepository boardAttachmentRepository;
	private final BoardMapper boardMapper;
	private final UploadSessionFailureService uploadSessionFailureService;
	private final long expirationMs;

	public UploadSessionService(
		UploadSessionRepository uploadSessionRepository,
		UploadSessionPartRepository uploadSessionPartRepository,
		UploadSessionStorageService uploadSessionStorageService,
		AttachmentStorageService attachmentStorageService,
		BoardPostRepository boardPostRepository,
		BoardAttachmentRepository boardAttachmentRepository,
		BoardMapper boardMapper,
		UploadSessionFailureService uploadSessionFailureService,
		@org.springframework.beans.factory.annotation.Value("${app.upload-sessions.expiration-ms:86400000}") long expirationMs
	) {
		this.uploadSessionRepository = uploadSessionRepository;
		this.uploadSessionPartRepository = uploadSessionPartRepository;
		this.uploadSessionStorageService = uploadSessionStorageService;
		this.attachmentStorageService = attachmentStorageService;
		this.boardPostRepository = boardPostRepository;
		this.boardAttachmentRepository = boardAttachmentRepository;
		this.boardMapper = boardMapper;
		this.uploadSessionFailureService = uploadSessionFailureService;
		this.expirationMs = expirationMs;
	}

	public UploadSessionStatusSnapshot createSession(String username, CreateUploadSessionRequest request) {
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
			now.plusMillis(expirationMs)
		));
		return toStatusSnapshot(session, List.of());
	}

	public UploadSessionStatusSnapshot getSession(String username, UUID sessionId) {
		UploadSession session = findActiveSession(sessionId, username);
		return toStatusSnapshot(session, uploadedChunkNumbers(sessionId));
	}

	public UploadSessionStatusSnapshot uploadChunk(String username, UUID sessionId, int chunkNumber, String chunkDataBase64) {
		UploadSession session = findActiveSession(sessionId, username);
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

	public BoardPostDetailResponse finalizeSession(String username, UUID sessionId) {
		UploadSession session = findActiveSession(sessionId, username);
		List<UploadSessionPart> chunks = uploadSessionPartRepository.findBySession_IdOrderByChunkNumberAsc(sessionId);
		validateChunks(session, chunks);

		Instant now = Instant.now();
		session.markFinalizing(now);
		Path assembledPath = uploadSessionStorageService.createAssembledTarget(sessionId, session.getArchiveName());
		String storedAttachmentPath = null;

		try {
			uploadSessionStorageService.concatenate(
				assembledPath,
				chunks.stream().map(chunk -> uploadSessionStorageService.resolve(chunk.getStoragePath())).toList()
			);

			validateAssembledFile(session, assembledPath);

			BoardPost post = boardPostRepository.save(new BoardPost(
				buildTitle(session.getArchiveName()),
				buildBody(
					session.getArchiveName(),
					session.getFileSizeBytes(),
					session.getTotalChunks(),
					session.getFileSha256()
				),
				BoardPostMode.FILE_CONVERSION_REQUEST,
				now,
				now
			));

			AttachmentStorageService.StoredAttachment storedAttachment = attachmentStorageService.store(
				assembledPath,
				session.getArchiveName(),
				"application/zip"
			);
			storedAttachmentPath = storedAttachment.storagePath();

			BoardAttachment attachment = boardAttachmentRepository.save(new BoardAttachment(
				post,
				storedAttachment.originalFilename(),
				storedAttachment.storedFilename(),
				storedAttachment.storagePath(),
				storedAttachment.contentType(),
				storedAttachment.size(),
				now
			));

			session.markCompleted(now);
			String finalStoredAttachmentPath = storedAttachmentPath;
			deleteSessionRows(session);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					uploadSessionStorageService.deleteSessionDirectory(session.getId());
				}

				@Override
				public void afterCompletion(int status) {
					if (status != STATUS_COMMITTED && finalStoredAttachmentPath != null) {
						attachmentStorageService.deleteIfExists(finalStoredAttachmentPath);
					}
				}
			});

			return boardMapper.toDetailResponse(post, attachment);
		} catch (RuntimeException exception) {
			uploadSessionFailureService.markFailed(session.getId(), Instant.now());
			deleteAssembledFileIfExists(assembledPath);
			if (storedAttachmentPath != null) {
				attachmentStorageService.deleteIfExists(storedAttachmentPath);
			}
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

	private UploadSession findActiveSession(UUID sessionId, String username) {
		UploadSession session = uploadSessionRepository.findById(sessionId)
			.orElseThrow(() -> new UploadSessionNotFoundException(sessionId));
		if (!session.getCreatedBy().equals(username)) {
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
		if (request.fileSizeBytes() > attachmentStorageService.getMaxGeneratedFileSizeBytes()) {
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
		return Path.of(archiveName).getFileName().toString();
	}

	private String buildTitle(String archiveName) {
		return "[" + archiveName + "] 업로드 완료";
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

	private void deleteSessionRows(UploadSession session) {
		uploadSessionPartRepository.deleteBySession(session);
		uploadSessionRepository.delete(session);
	}

	private void deleteAssembledFileIfExists(Path assembledPath) {
		try {
			Files.deleteIfExists(assembledPath);
		} catch (IOException exception) {
			// Best-effort cleanup for failed finalize attempts.
		}
	}
}
