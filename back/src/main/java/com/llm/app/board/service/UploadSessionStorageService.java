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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

@Component
public class UploadSessionStorageService {
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
		String storedFilename = "chunk-%06d-%s".formatted(chunkNumber, safeOriginalFilename);
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
		Path assembledPath = sessionDir.resolve("assembled-" + Path.of(archiveName).getFileName());
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
		try (var walk = Files.walk(sessionDir)) {
			walk.sorted(Comparator.reverseOrder())
				.forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException exception) {
						throw new AttachmentStorageException("Failed to delete upload session files", exception);
					}
				});
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to delete upload session files", exception);
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
