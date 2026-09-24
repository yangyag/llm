package com.llm.app.upload.service;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.llm.app.upload.exception.UploadSessionChunkTooLargeException;
import com.llm.app.upload.exception.UploadSessionStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

@Component
public class UploadSessionStorageService {
	private static final Logger log = LoggerFactory.getLogger(UploadSessionStorageService.class);

	private final Path rootPath;
	private final long maxDecodedChunkSizeBytes;

	/**
	 * 업로드 세션 파일의 루트 경로와 디코딩된 청크 최대 크기를 설정한다.
	 *
	 * @param rootPath 업로드 세션 파일을 저장할 루트 경로
	 * @param maxDecodedChunkSize 디코딩된 청크 하나의 최대 허용 크기
	 */
	public UploadSessionStorageService(
		@Value("${app.upload-sessions.root-path:${java.io.tmpdir}/llm-upload-sessions}") String rootPath,
		@Value("${app.upload-sessions.max-decoded-chunk-size:8MB}") DataSize maxDecodedChunkSize
	) {
		this.rootPath = Paths.get(rootPath).toAbsolutePath().normalize();
		long transmittable = maxTransmittableDecodedChunkSize(StreamReadConstraints.defaults().getMaxStringLength());
		if (maxDecodedChunkSize.toBytes() > transmittable) {
			log.warn(
				"app.upload-sessions.max-decoded-chunk-size {} exceeds what one encrypted JSON chunk field can carry; using {} bytes",
				maxDecodedChunkSize,
				transmittable
			);
		}
		this.maxDecodedChunkSizeBytes = Math.min(maxDecodedChunkSize.toBytes(), transmittable);
	}

	/**
	 * 암호화된 청크 필드(A11) 하나가 JSON 문자열 길이 한도 안에 들어가는 최대 decode 크기를 계산한다.
	 *
	 * <p>A11은 base64url(패딩 없음) 인코딩한 {@code version(1) + nonce(12) + AES-GCM(JSON 문자열) + tag(16)}이고,
	 * JSON 문자열은 청크 Base64 앞뒤에 따옴표 2개가 붙는다. 이보다 큰 청크는 컨트롤러가 요청 본문을 읽는
	 * 단계에서 Jackson {@code StreamReadConstraints}에 걸려 모든 업로드가 400으로 실패한다.</p>
	 *
	 * @param maxJsonStringLength JSON 문자열 값의 최대 길이
	 * @return 전송 가능한 청크의 최대 decode 바이트 수
	 */
	static long maxTransmittableDecodedChunkSize(int maxJsonStringLength) {
		long maxPayloadBytes = (long) maxJsonStringLength * 3 / 4;
		long maxChunkBase64Chars = maxPayloadBytes - 1 - 12 - 16 - 2;
		return (maxChunkBase64Chars / 4) * 3;
	}

	/**
	 * 업로드 청크를 세션 디렉터리에 저장한다.
	 *
	 * @param sessionId 대상 업로드 세션 ID
	 * @param chunkNumber 저장할 청크 번호
	 * @param originalFilename 원본 파일명
	 * @param bytes 저장할 디코딩된 청크 바이트
	 * @return 저장된 청크의 파일 메타데이터
	 * @throws UploadSessionChunkTooLargeException 청크가 허용 크기를 초과한 경우
	 * @throws UploadSessionStorageException 파일 저장에 실패한 경우
	 */
	public StoredUploadPart store(UUID sessionId, int chunkNumber, String originalFilename, byte[] bytes) {
		if (bytes.length > maxDecodedChunkSizeBytes) {
			throw new UploadSessionChunkTooLargeException(maxDecodedChunkSizeBytes);
		}

		String safeOriginalFilename = extractOriginalFilename(originalFilename);
		// ponytail: chunk blobs are keyed by (sessionId, chunkNumber); the human name lives in
		// original_filename. Embedding archiveName here pushed stored_filename past varchar(255) and the
		// OS filename-length limit for long/multibyte names.
		String storedFilename = "chunk-%06d".formatted(chunkNumber);
		String storagePath = sessionId + "/" + storedFilename;
		Path targetPath = resolve(storagePath);

		try {
			Files.createDirectories(targetPath.getParent());
			Files.write(targetPath, bytes);
		} catch (IOException exception) {
			throw new UploadSessionStorageException("Failed to store upload session chunk", exception);
		}

		return new StoredUploadPart(safeOriginalFilename, storedFilename, storagePath, bytes.length);
	}

	/**
	 * 저장소 루트를 기준으로 상대 저장 경로를 정규화된 파일 경로로 변환한다.
	 *
	 * @param storagePath 루트 기준 상대 저장 경로
	 * @return 정규화된 파일 경로
	 */
	public Path resolve(String storagePath) {
		return rootPath.resolve(storagePath).normalize();
	}

	/**
	 * 청크를 이어 붙일 고유한 조립 대상 파일 경로를 생성한다.
	 *
	 * @param sessionId 대상 업로드 세션 ID
	 * @param archiveName 원본 아카이브 파일명(호환성을 위해 받지만 경로에는 사용하지 않음)
	 * @return 조립 결과를 저장할 파일 경로
	 * @throws UploadSessionStorageException 세션 디렉터리 준비에 실패한 경우
	 */
	public Path createAssembledTarget(UUID sessionId, String archiveName) {
		Path sessionDir = resolve(sessionId.toString());
		// ponytail: unique per finalize so concurrent/retried finalizes never share one assembled file.
		// A shared fixed path was a hash-validate-then-copy TOCTOU -> a corrupt stored attachment whose bytes
		// don't match the advertised SHA-256. archiveName is omitted (unbounded/multibyte; the human name
		// lives on the final attachment).
		Path assembledPath = sessionDir.resolve("assembled-" + UUID.randomUUID());
		try {
			Files.createDirectories(sessionDir);
		} catch (IOException exception) {
			throw new UploadSessionStorageException("Failed to prepare upload session assembly path", exception);
		}
		return assembledPath;
	}

	/**
	 * 여러 청크 파일을 순서대로 읽어 하나의 대상 파일로 이어 붙인다.
	 *
	 * @param targetPath 조립 결과를 기록할 파일 경로
	 * @param sourcePaths 순서대로 읽을 청크 파일 경로 목록
	 * @throws UploadSessionStorageException 청크 읽기 또는 결과 파일 쓰기에 실패한 경우
	 */
	public void concatenate(Path targetPath, Iterable<Path> sourcePaths) {
		try (var outputStream = Files.newOutputStream(targetPath)) {
			for (Path sourcePath : sourcePaths) {
				try (InputStream inputStream = Files.newInputStream(sourcePath)) {
					inputStream.transferTo(outputStream);
				}
			}
		} catch (IOException exception) {
			throw new UploadSessionStorageException("Failed to assemble upload session chunks", exception);
		}
	}

	/**
	 * 세션 디렉터리와 내부 파일을 best-effort 방식으로 삭제한다.
	 *
	 * <p>정리 실패는 이미 커밋된 업로드 처리 결과를 되돌리지 않도록 로그만 남긴다.</p>
	 *
	 * @param sessionId 삭제할 업로드 세션 ID
	 */
	public void deleteSessionDirectory(UUID sessionId) {
		Path sessionDir = resolve(sessionId.toString());
		if (!Files.exists(sessionDir)) {
			return;
		}
		// Best-effort: runs in afterCommit synchronizations after the rows are already deleted; throwing here
		// would poison the cleanup batch (skipping later sessions whose rows are gone) and turn a committed
		// finalize into a 500. Swallow + log instead.
		try (var walk = Files.walk(sessionDir)) {
			walk.sorted(Comparator.reverseOrder())
				.forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException exception) {
						log.warn("Best-effort delete failed for upload session file {}", path, exception);
					}
				});
		} catch (IOException exception) {
			log.warn("Best-effort cleanup failed for upload session directory {}", sessionDir, exception);
		}
	}

	/**
	 * 디코딩된 청크 하나에 허용되는 최대 바이트 수를 반환한다.
	 *
	 * @return 디코딩된 청크 최대 크기(바이트)
	 */
	public long getMaxDecodedChunkSizeBytes() {
		return maxDecodedChunkSizeBytes;
	}

	/**
	 * 원본 경로에서 파일명 부분만 추출한다.
	 *
	 * @param filename 원본 파일명 또는 경로
	 * @return 경로가 제거된 파일명, 입력이 비어 있으면 기본 파일명
	 */
	private String extractOriginalFilename(String filename) {
		if (!StringUtils.hasText(filename)) {
			return "chunk.bin";
		}
		return Path.of(filename).getFileName().toString();
	}

	/** 저장된 업로드 청크의 원본 및 저장 위치 메타데이터. */
	public record StoredUploadPart(
		String originalFilename,
		String storedFilename,
		String storagePath,
		long size
	) {
	}
}
