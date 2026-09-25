package com.llm.app.board.service;

import com.llm.app.board.exception.AttachmentTooLargeException;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@Component
public class InlineImageValidator {
	public static final int MAX_WIDTH = 8192;
	public static final int MAX_HEIGHT = 8192;
	public static final long MAX_PIXELS = 25_000_000L;
	static final long MAX_DECODED_PIXELS = 1_000_000L;

	private final long maxFileSizeBytes;

	public InlineImageValidator(
		@Value("${app.attachments.inline-images.max-file-size:10MB}") DataSize maxFileSize
	) {
		if (maxFileSize.toBytes() <= 0) {
			throw new IllegalArgumentException("inline image max file size must be positive");
		}
		this.maxFileSizeBytes = maxFileSize.toBytes();
	}

	public ValidatedInlineImage validate(MultipartFile file) {
		if (file == null || file.getSize() <= 0) {
			throw new InvalidAttachmentRequestException("inline image must not be empty");
		}
		if (file.getSize() > maxFileSizeBytes) {
			throw new AttachmentTooLargeException(maxFileSizeBytes);
		}
		ImageReader reader = null;
		try (InputStream input = file.getInputStream();
			ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext()) {
				throw new InvalidAttachmentRequestException("inline image must be a PNG or JPEG image");
			}
			reader = readers.next();
			String formatName = reader.getFormatName().toLowerCase(Locale.ROOT);
			String contentType;
			String extension;
			if ("png".equals(formatName)) {
				contentType = "image/png";
				extension = ".png";
			} else if ("jpeg".equals(formatName) || "jpg".equals(formatName)) {
				contentType = "image/jpeg";
				extension = ".jpg";
			} else {
				throw new InvalidAttachmentRequestException("inline image must be a PNG or JPEG image");
			}
			reader.setInput(imageInput, true, true);
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			if (width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT
				|| (long) width * height > MAX_PIXELS) {
				throw new InvalidAttachmentRequestException("inline image dimensions exceed the allowed limits");
			}
			// 원본 크기로 펼치면 16비트 RGBA 2,500만 픽셀이 약 200MB라 힙(512MB)을 위협한다.
			// subsampling해도 decoder는 모든 행을 읽고 풀기 때문에 손상 검출은 그대로 유지된다.
			ImageReadParam param = reader.getDefaultReadParam();
			int subsampling = decodeSubsampling(width, height);
			param.setSourceSubsampling(subsampling, subsampling, 0, 0);
			reader.read(0, param);
			return new ValidatedInlineImage(contentType, extension, width, height);
		} catch (InvalidAttachmentRequestException | AttachmentTooLargeException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw new InvalidAttachmentRequestException("inline image could not be read");
		} finally {
			if (reader != null) {
				reader.dispose();
			}
		}
	}

	static int decodeSubsampling(int width, int height) {
		int factor = 1;
		while ((long) Math.ceilDiv(width, factor) * Math.ceilDiv(height, factor) > MAX_DECODED_PIXELS) {
			factor++;
		}
		return factor;
	}

	public record ValidatedInlineImage(String contentType, String extension, int width, int height) {
	}
}
