package com.llm.app.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.board.exception.AttachmentTooLargeException;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
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

	@Test
	void shouldBoundDecodedPixelsWithSubsampling() {
		assertThat(InlineImageValidator.decodeSubsampling(1000, 1000)).isEqualTo(1);
		assertThat(InlineImageValidator.decodeSubsampling(8192, 1)).isEqualTo(1);
		assertThat(InlineImageValidator.decodeSubsampling(1001, 1000)).isEqualTo(2);
		assertThat(InlineImageValidator.decodeSubsampling(5000, 5000)).isEqualTo(5);
		for (int[] size : new int[][] { { 5000, 5000 }, { 8192, 3051 }, { 4999, 5000 }, { 1001, 1000 } }) {
			int factor = InlineImageValidator.decodeSubsampling(size[0], size[1]);
			assertThat((long) Math.ceilDiv(size[0], factor) * Math.ceilDiv(size[1], factor))
				.isLessThanOrEqualTo(InlineImageValidator.MAX_DECODED_PIXELS);
		}
	}

	@Test
	void shouldValidateMaxPixelSixteenBitPngWithoutFullSizeDecodeAllocation() throws IOException {
		// 원본 크기 decode면 5000x5000 16비트 RGBA 하나에 200MB를 할당한다.
		byte[] pngBytes = zeroRgba16Png(5000, 5000);
		com.sun.management.ThreadMXBean threads =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long before = threads.getCurrentThreadAllocatedBytes();

		assertAccepted("max.png", pngBytes, 5000, 5000);

		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertThat(allocated).isLessThan(64L * 1024 * 1024);
	}

	@Test
	void shouldStillRejectImageDataTruncatedWhenDecodingIsSubsampled() {
		byte[] pngBytes = patternedImageBytes("png", 2000, 1000);
		assertThat(InlineImageValidator.decodeSubsampling(2000, 1000)).isGreaterThan(1);
		assertAccepted("pattern.png", pngBytes, 2000, 1000);
		assertRejected(Arrays.copyOf(pngBytes, pngBytes.length * 7 / 10));

		byte[] jpegBytes = patternedImageBytes("jpeg", 2000, 1000);
		assertThat(validator.validate(new MockMultipartFile("inlineImages", "pattern.jpg", "image/jpeg", jpegBytes))
			.contentType()).isEqualTo("image/jpeg");
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

	private byte[] patternedImageBytes(String format, int width, int height) {
		try {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setRGB(x, y, (x * 7919) ^ (y * 104729));
				}
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(image, format, output)) throw new IllegalStateException("missing image writer");
			return output.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("failed to build image fixture", exception);
		}
	}

	private byte[] zeroRgba16Png(int width, int height) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' });
		writePngChunk(output, "IHDR", ByteBuffer.allocate(13)
			.putInt(width).putInt(height).put((byte) 16).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0)
			.array());
		ByteArrayOutputStream pixels = new ByteArrayOutputStream();
		try (DeflaterOutputStream deflate = new DeflaterOutputStream(pixels)) {
			byte[] row = new byte[1 + width * 8];
			for (int y = 0; y < height; y++) {
				deflate.write(row);
			}
		}
		writePngChunk(output, "IDAT", pixels.toByteArray());
		writePngChunk(output, "IEND", new byte[0]);
		return output.toByteArray();
	}

	private void writePngChunk(ByteArrayOutputStream output, String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		output.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		output.writeBytes(typeBytes);
		output.writeBytes(data);
		output.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
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
