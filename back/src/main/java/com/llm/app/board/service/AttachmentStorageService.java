package com.llm.app.board.service;

import com.llm.app.board.exception.AttachmentStorageException;
import com.llm.app.board.exception.AttachmentTooLargeException;
import com.llm.app.board.model.BoardAttachment;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@Component
public class AttachmentStorageService {
	private final Path rootPath;
	private final long maxUploadFileSizeBytes;
	private final long maxGeneratedFileSizeBytes;

	public AttachmentStorageService(
		@Value("${app.attachments.root-path:${java.io.tmpdir}/llm-attachments}") String rootPath,
		@Value("${spring.servlet.multipart.max-file-size:100MB}") DataSize maxUploadFileSize,
		@Value("${app.attachments.max-generated-file-size:2GB}") DataSize maxGeneratedFileSize
	) {
		this.rootPath = Paths.get(rootPath).toAbsolutePath().normalize();
		this.maxUploadFileSizeBytes = maxUploadFileSize.toBytes();
		this.maxGeneratedFileSizeBytes = maxGeneratedFileSize.toBytes();
	}

	public StoredAttachment store(MultipartFile attachment) {
		if (attachment.getSize() > maxUploadFileSizeBytes) {
			throw new AttachmentTooLargeException(maxUploadFileSizeBytes);
		}

		String originalFilename = extractOriginalFilename(attachment);
		String contentType = attachment.getContentType();
		String extension = extractExtension(originalFilename);
		String storedFilename = UUID.randomUUID() + extension;
		String storagePath = storedFilename;
		Path targetPath = resolve(storagePath);

		try {
			Files.createDirectories(this.rootPath);
			attachment.transferTo(targetPath);
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to store attachment", exception);
		}

		return new StoredAttachment(
			originalFilename,
			storedFilename,
			storagePath,
			contentType,
			attachment.getSize()
		);
	}

	public StoredAttachment store(String originalFilename, String contentType, byte[] bytes) {
		if (bytes.length > maxGeneratedFileSizeBytes) {
			throw new AttachmentTooLargeException(maxGeneratedFileSizeBytes);
		}

		String extension = extractExtension(originalFilename);
		String storedFilename = UUID.randomUUID() + extension;
		String storagePath = storedFilename;
		Path targetPath = resolve(storagePath);

		try {
			Files.createDirectories(this.rootPath);
			Files.write(targetPath, bytes, StandardOpenOption.CREATE_NEW);
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to store attachment", exception);
		}

		return new StoredAttachment(
			originalFilename,
			storedFilename,
			storagePath,
			contentType,
			bytes.length
		);
	}

	public StoredAttachment store(Path sourceFile, String originalFilename, String contentType) {
		long size;
		try {
			size = Files.size(sourceFile);
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to read generated attachment size", exception);
		}

		if (size > maxGeneratedFileSizeBytes) {
			throw new AttachmentTooLargeException(maxGeneratedFileSizeBytes);
		}

		String extension = extractExtension(originalFilename);
		String storedFilename = UUID.randomUUID() + extension;
		String storagePath = storedFilename;
		Path targetPath = resolve(storagePath);

		try {
			Files.createDirectories(this.rootPath);
			Files.copy(sourceFile, targetPath);
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to store generated attachment", exception);
		}

		return new StoredAttachment(
			originalFilename,
			storedFilename,
			storagePath,
			contentType,
			size
		);
	}

	public Resource loadAsResource(BoardAttachment attachment) {
		Path filePath = resolve(attachment.getStoragePath());
		try {
			Resource resource = new UrlResource(filePath.toUri());
			if (!resource.exists() || !resource.isReadable()) {
				throw new AttachmentStorageException("Failed to load attachment", new IOException("Attachment file is missing"));
			}
			return resource;
		} catch (MalformedURLException exception) {
			throw new AttachmentStorageException("Failed to load attachment", exception);
		}
	}

	public void deleteIfExists(String storagePath) {
		try {
			Files.deleteIfExists(resolve(storagePath));
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to delete attachment", exception);
		}
	}

	private Path resolve(String storagePath) {
		return rootPath.resolve(storagePath).normalize();
	}

	private String extractOriginalFilename(MultipartFile attachment) {
		String filename = attachment.getOriginalFilename();
		if (!StringUtils.hasText(filename)) {
			return "attachment";
		}
		return Path.of(filename).getFileName().toString();
	}

	private String extractExtension(String filename) {
		int dotIndex = filename.lastIndexOf('.');
		if (dotIndex < 0 || dotIndex == filename.length() - 1) {
			return "";
		}
		return filename.substring(dotIndex);
	}

	public long getMaxGeneratedFileSizeBytes() {
		return maxGeneratedFileSizeBytes;
	}

	public record StoredAttachment(
		String originalFilename,
		String storedFilename,
		String storagePath,
		String contentType,
		long size
	) {
	}
}
