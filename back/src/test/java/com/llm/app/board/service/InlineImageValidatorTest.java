package com.llm.app.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.board.exception.AttachmentTooLargeException;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class InlineImageValidatorTest {
	private final InlineImageValidator validator = new InlineImageValidator(DataSize.ofMegabytes(10));

	@Test
	void shouldReturnVerifiedTypeExtensionAndDimensionsIgnoringClientMime() {
		byte[] pngBytes = imageBytes("png", 2, 3);
		InlineImageValidator.ValidatedInlineImage png = validator.validate(
			new MockMultipartFile("inlineImages", "capture.html", "text/html", pngBytes));
		assertThat(png.contentType()).isEqualTo("image/png");
		assertThat(png.extension()).isEqualTo(".png");
		assertThat(png.width()).isEqualTo(2);
		assertThat(png.height()).isEqualTo(3);

		byte[] jpegBytes = imageBytes("jpeg", 3, 2);
		InlineImageValidator.ValidatedInlineImage jpeg = validator.validate(
			new MockMultipartFile("inlineImages", "photo.bin", "application/octet-stream", jpegBytes));
		assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
		assertThat(jpeg.extension()).isEqualTo(".jpg");
		assertThat(jpeg.width()).isEqualTo(3);
		assertThat(jpeg.height()).isEqualTo(2);
	}

	@Test
	void shouldRejectNonImageBytesAndUnsupportedFormats() {
		assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
				"inlineImages", "fake.png", "image/png", "not a real png".getBytes(StandardCharsets.UTF_8))))
			.isInstanceOf(InvalidAttachmentRequestException.class);
		assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
				"inlineImages", "anim.gif", "image/gif", imageBytes("gif", 2, 2))))
			.isInstanceOf(InvalidAttachmentRequestException.class);
		assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
				"inlineImages", "empty.png", "image/png", new byte[0])))
			.isInstanceOf(InvalidAttachmentRequestException.class);
	}

	@Test
	void shouldRejectFileLargerThanConfiguredLimit() {
		byte[] pngBytes = imageBytes("png", 2, 2);
		InlineImageValidator strictValidator = new InlineImageValidator(DataSize.ofBytes(pngBytes.length - 1));

		assertThatThrownBy(() -> strictValidator.validate(new MockMultipartFile(
				"inlineImages", "big.png", "image/png", pngBytes)))
			.isInstanceOf(AttachmentTooLargeException.class);
	}

	@Test
	void shouldRejectDimensionsAndPixelCountOverLimits() {
		assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
				"inlineImages", "wide.png", "image/png", pngWithDimensions(imageBytes("png", 2, 2), 8193, 1))))
			.isInstanceOf(InvalidAttachmentRequestException.class);
		assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
				"inlineImages", "huge.png", "image/png", pngWithDimensions(imageBytes("png", 2, 2), 5001, 5000))))
			.isInstanceOf(InvalidAttachmentRequestException.class);
	}

	@Test
	void shouldAcceptFilesUpToConfiguredTenMebibyteLimit() {
		int maxBytes = (int) DataSize.ofMegabytes(10).toBytes();
		byte[] pngBytes = imageBytes("png", 2, 2);
		for (int size : new int[] { maxBytes - 1, maxBytes }) {
			byte[] padded = Arrays.copyOf(pngBytes, size);
			InlineImageValidator.ValidatedInlineImage result = validator.validate(
				new MockMultipartFile("inlineImages", "padded.png", "image/png", padded));
			assertThat(result.contentType()).isEqualTo("image/png");
			assertThat(result.width()).isEqualTo(2);
			assertThat(result.height()).isEqualTo(2);
		}
		assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
				"inlineImages", "over.png", "image/png", Arrays.copyOf(pngBytes, maxBytes + 1))))
			.isInstanceOf(AttachmentTooLargeException.class);
	}

	@Test
	void shouldAcceptDimensionBoundariesAndRejectOverflow() {
		assertAccepted("8191x1.png", imageBytes("png", 8191, 1), 8191, 1);
		assertAccepted("8192x1.png", imageBytes("png", 8192, 1), 8192, 1);
		assertAccepted("1x8192.png", imageBytes("png", 1, 8192), 1, 8192);
		assertRejected(pngWithDimensions(imageBytes("png", 2, 2), 8193, 1));
		assertRejected(pngWithDimensions(imageBytes("png", 2, 2), 1, 8193));
	}

	@Test
	void shouldAcceptPixelCountBoundariesAndRejectOverflow() {
		assertAccepted("4999x5000.png", binaryImageBytes(4999, 5000), 4999, 5000);
		assertAccepted("5000x5000.png", binaryImageBytes(5000, 5000), 5000, 5000);
		assertRejected(pngWithDimensions(imageBytes("png", 2, 2), 5001, 5000));
	}

	@Test
	void shouldRejectMarkupPayloadsAndTruncatedPng() {
		assertRejected("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
			.getBytes(StandardCharsets.UTF_8));
		assertRejected("<html><body><img src=x onerror=alert(1)></body></html>"
			.getBytes(StandardCharsets.UTF_8));
		byte[] pngBytes = imageBytes("png", 2, 2);
		assertThat(pngBytes.length).isGreaterThan(33);
		assertRejected(Arrays.copyOf(pngBytes, 33));
	}

	private void assertAccepted(String name, byte[] bytes, int width, int height) {
		InlineImageValidator.ValidatedInlineImage result = validator.validate(
			new MockMultipartFile("inlineImages", name, "image/png", bytes));
		assertThat(result.contentType()).isEqualTo("image/png");
		assertThat(result.width()).isEqualTo(width);
		assertThat(result.height()).isEqualTo(height);
	}

	private void assertRejected(byte[] bytes) {
		assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
				"inlineImages", "rejected.png", "image/png", bytes)))
			.isInstanceOf(InvalidAttachmentRequestException.class);
	}

	private byte[] binaryImageBytes(int width, int height) {
		try {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("missing image writer");
			return output.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("failed to build image fixture", exception);
		}
	}

	private byte[] imageBytes(String format, int width, int height) {
		try {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(image, format, output)) throw new IllegalStateException("missing image writer");
			return output.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("failed to build image fixture", exception);
		}
	}

	private byte[] pngWithDimensions(byte[] png, int width, int height) {
		byte[] patched = png.clone();
		ByteBuffer.wrap(patched).putInt(16, width).putInt(20, height);
		CRC32 crc = new CRC32();
		crc.update(patched, 12, 17);
		ByteBuffer.wrap(patched).putInt(29, (int) crc.getValue());
		return patched;
	}
}
