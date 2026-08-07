package com.llm.app.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.board.exception.InvalidEncodedBodyException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class BoardContentCodecTest {

	private final BoardContentCodec codec = new BoardContentCodec();

	@Test
	void decodeBodyShouldReturnOriginalText() {
		String body = "안녕하세요, 본문입니다.\n줄바꿈 포함";
		assertThat(codec.decodeBody(encode(body))).isEqualTo(body);
	}

	@Test
	void decodeBodyShouldRejectBlank() {
		assertThatThrownBy(() -> codec.decodeBody(encode("   ")))
			.isInstanceOf(InvalidEncodedBodyException.class);
	}

	@Test
	void decodeBodyShouldRejectOverOneMillionChars() {
		assertThatThrownBy(() -> codec.decodeBody(encode("a".repeat(1_000_001))))
			.isInstanceOf(InvalidEncodedBodyException.class);
	}

	@Test
	void decodeOptionalBodyShouldAllowEmpty() {
		assertThat(codec.decodeOptionalBody(null)).isEmpty();
		assertThat(codec.decodeOptionalBody("")).isEmpty();
		assertThat(codec.decodeOptionalBody(encode("  "))).isEqualTo("  ");
	}

	@Test
	void decodeOptionalBodyShouldRejectOverOneMillionChars() {
		assertThatThrownBy(() -> codec.decodeOptionalBody(encode("a".repeat(1_000_001))))
			.isInstanceOf(InvalidEncodedBodyException.class);
	}

	@Test
	void decodeBinaryShouldRejectInvalidBase64() {
		assertThatThrownBy(() -> codec.decodeBinary("%%%bad%%%"))
			.isInstanceOf(InvalidEncodedBodyException.class);
	}

	@Test
	void decodeBinaryShouldRoundTripBytes() {
		byte[] raw = new byte[] { 1, 2, 3, (byte) 255 };
		assertThat(codec.decodeBinary(Base64.getEncoder().encodeToString(raw))).isEqualTo(raw);
	}

	private static String encode(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
