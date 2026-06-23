package com.llm.app.board.service;

import com.llm.app.board.exception.AttachmentStorageException;
import com.llm.app.board.exception.AttachmentTooLargeException;
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

	public UploadSessionStorageService(
		@Value("${app.upload-sessions.root-path:${java.io.tmpdir}/llm-upload-sessions}") String rootPath,
		@Value("${app.upload-sessions.max-decoded-chunk-size:100MB}") DataSize maxDecodedChunkSize
	) {
		this.rootPath = Paths.get(rootPath).toAbsolutePath().normalize();
		this.maxDecodedChunkSizeBytes = maxDecodedChunkSize.toBytes();
	}

	public StoredUploadPart store(UUID sessionId, int chunkNumber, String originalFilename, byte[] bytes) {
		if (bytes.length > maxDecodedChunkSizeBytes) {
			throw new AttachmentTooLargeException(maxDecodedChunkSizeBytes);
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
			throw new AttachmentStorageException("Failed to store upload session chunk", exception);
		}

		return new StoredUploadPart(safeOriginalFilename, storedFilename, storagePath, bytes.length);
	}

	public Path resolve(String storagePath) {
		return rootPath.resolve(storagePath).normalize();
	}

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
			throw new AttachmentStorageException("Failed to prepare upload session assembly path", exception);
		}
		return assembledPath;
	}

	public void concatenate(Path targetPath, Iterable<Path> sourcePaths) {
		try (var outputStream = Files.newOutputStream(targetPath)) {
			for (Path sourcePath : sourcePaths) {
				try (InputStream inputStream = Files.newInputStream(sourcePath)) {
					inputStream.transferTo(outputStream);
				}
			}
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to assemble upload session chunks", exception);
		}
	}

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

	public long getMaxDecodedChunkSizeBytes() {
		return maxDecodedChunkSizeBytes;
	}

	private String extractOriginalFilename(String filename) {
		if (!StringUtils.hasText(filename)) {
			return "chunk.bin";
		}
		return Path.of(filename).getFileName().toString();
	}

	public record StoredUploadPart(
		String originalFilename,
		String storedFilename,
		String storagePath,
		long size
	) {
	}
}
