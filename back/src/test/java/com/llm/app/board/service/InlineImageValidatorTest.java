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
