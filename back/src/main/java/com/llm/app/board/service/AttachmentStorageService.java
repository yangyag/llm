package com.llm.app.board.service;

import com.llm.app.board.api.upload.GeneratedAttachmentPolicy;
import com.llm.app.board.exception.AttachmentStorageException;
import com.llm.app.board.exception.AttachmentTooLargeException;
import com.llm.app.board.model.BoardAttachment;
import java.io.IOException;
import java.net.MalformedURLException;
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
public class AttachmentStorageService implements GeneratedAttachmentPolicy {
	private final Path rootPath;
	private final long maxUploadFileSizeBytes;
	private final long maxGeneratedFileSizeBytes;

	/**
	 * 첨부파일 저장소의 경로와 파일 크기 제한을 초기화한다.
	 *
	 * @param rootPath 첨부파일을 저장할 루트 경로
	 * @param maxUploadFileSize 일반 업로드 파일의 최대 크기
	 * @param maxGeneratedFileSize 생성된 파일의 최대 크기
	 */
	public AttachmentStorageService(
		@Value("${app.attachments.root-path:${java.io.tmpdir}/llm-attachments}") String rootPath,
		@Value("${spring.servlet.multipart.max-file-size:100MB}") DataSize maxUploadFileSize,
		@Value("${app.attachments.max-generated-file-size:2GB}") DataSize maxGeneratedFileSize
	) {
		this.rootPath = Paths.get(rootPath).toAbsolutePath().normalize();
		this.maxUploadFileSizeBytes = maxUploadFileSize.toBytes();
		this.maxGeneratedFileSizeBytes = maxGeneratedFileSize.toBytes();
	}

	/**
	 * 멀티파트 업로드 파일을 무작위 이름으로 저장한다.
	 *
	 * @param attachment 저장할 멀티파트 파일
	 * @return 저장된 파일의 원본명·저장명·경로·형식·크기 정보
	 * @throws AttachmentTooLargeException 업로드 파일이 최대 크기를 초과한 경우
	 * @throws AttachmentStorageException 파일 저장에 실패한 경우
	 */
	public StoredAttachment store(MultipartFile attachment) {
		validateUploadSize(attachment);
		String originalFilename = extractOriginalFilename(attachment);
		return storeMultipart(
			attachment,
			originalFilename,
			attachment.getContentType(),
			extractExtension(originalFilename)
		);
	}

	public StoredAttachment store(MultipartFile attachment, String verifiedContentType, String verifiedExtension) {
		validateUploadSize(attachment);
		boolean png = "image/png".equals(verifiedContentType) && ".png".equals(verifiedExtension);
		boolean jpeg = "image/jpeg".equals(verifiedContentType) && ".jpg".equals(verifiedExtension);
		if (!png && !jpeg) {
			throw new IllegalArgumentException("unsupported verified inline image type");
		}
		return storeMultipart(attachment, extractOriginalFilename(attachment), verifiedContentType, verifiedExtension);
	}

	private void validateUploadSize(MultipartFile attachment) {
		if (attachment.getSize() > maxUploadFileSizeBytes) {
			throw new AttachmentTooLargeException(maxUploadFileSizeBytes);
		}
	}

	private StoredAttachment storeMultipart(
		MultipartFile attachment,
		String originalFilename,
		String contentType,
		String extension
	) {
		String storedFilename = UUID.randomUUID() + extension;
		String storagePath = storedFilename;
		Path targetPath = resolve(storagePath);

		try {
			Files.createDirectories(this.rootPath);
			attachment.transferTo(targetPath);
		} catch (IOException exception) {
			cleanupPartial(targetPath, exception);
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

	/**
	 * 생성된 파일을 첨부파일 저장소에 복사한다.
	 *
	 * @param sourceFile 복사할 원본 파일 경로
	 * @param originalFilename 사용자에게 표시할 원본 파일명
	 * @param contentType 파일 콘텐츠 유형
	 * @return 저장된 파일의 원본명·저장명·경로·형식·크기 정보
	 * @throws AttachmentTooLargeException 생성된 파일이 최대 크기를 초과한 경우
	 * @throws AttachmentStorageException 파일 크기 확인 또는 저장에 실패한 경우
	 */
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
			cleanupPartial(targetPath, exception);
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

	/**
	 * 첨부파일 메타데이터에 해당하는 실파일을 읽기 리소스로 반환한다.
	 *
	 * @param attachment 읽을 첨부파일 메타데이터
	 * @return 다운로드에 사용할 파일 리소스
	 * @throws AttachmentStorageException 파일이 없거나 읽을 수 없는 경우
	 */
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

	/**
	 * 지정한 저장 경로의 첨부파일을 존재할 때 삭제한다.
	 *
	 * @param storagePath 저장 루트 기준 첨부파일 경로
	 * @throws AttachmentStorageException 파일 삭제에 실패한 경우
	 */
	public void deleteIfExists(String storagePath) {
		try {
			Files.deleteIfExists(resolve(storagePath));
		} catch (IOException exception) {
			throw new AttachmentStorageException("Failed to delete attachment", exception);
		}
	}

	/**
	 * 저장 중 실패한 파일을 정리하고 원래 예외에 정리 실패를 추가한다.
	 *
	 * @param target 정리할 대상 경로
	 * @param original 저장 중 발생한 원래 예외
	 */
	private void cleanupPartial(Path target, IOException original) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException cleanupFailure) {
			original.addSuppressed(cleanupFailure);
		}
	}

	/**
	 * 저장 경로가 첨부파일 루트 내부를 가리키는지 검증하고 절대 경로로 변환한다.
	 *
	 * @param storagePath 저장 루트 기준 상대 경로
	 * @return 검증된 절대 경로
	 * @throws AttachmentStorageException 루트 밖으로 벗어나는 경로인 경우
	 */
	private Path resolve(String storagePath) {
		Path resolved = rootPath.resolve(storagePath).normalize();
		if (!resolved.startsWith(rootPath) || resolved.equals(rootPath)) {
			throw new AttachmentStorageException("Invalid attachment storage path", new IOException("Path outside attachment root"));
		}
		return resolved;
	}

	/**
	 * 멀티파트 파일명에서 경로를 제거한 안전한 원본 파일명을 추출한다.
	 *
	 * @param attachment 파일명이 포함된 멀티파트 파일
	 * @return 경로가 제거된 원본 파일명 또는 기본 파일명
	 */
	private String extractOriginalFilename(MultipartFile attachment) {
		String filename = attachment.getOriginalFilename();
		if (!StringUtils.hasText(filename)) {
			return "attachment";
		}
		return Path.of(filename).getFileName().toString();
	}

	/**
	 * 파일명에서 마지막 확장자를 추출한다.
	 *
	 * @param filename 확장자를 추출할 파일명
	 * @return 점을 포함한 확장자 또는 확장자가 없을 때 빈 문자열
	 */
	private String extractExtension(String filename) {
		int dotIndex = filename.lastIndexOf('.');
		if (dotIndex < 0 || dotIndex == filename.length() - 1) {
			return "";
		}
		return filename.substring(dotIndex);
	}

	/**
	 * 생성된 파일에 적용되는 최대 크기를 바이트 단위로 반환한다.
	 *
	 * @return 생성 파일 최대 크기(바이트)
	 */
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
