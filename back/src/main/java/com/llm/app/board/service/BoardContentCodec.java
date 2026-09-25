package com.llm.app.board.service;

import com.llm.app.board.exception.InvalidEncodedBodyException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class BoardContentCodec {
	private static final int MAX_BODY_LENGTH = 1_000_000;

	public String decodeBody(String bodyBase64) {
		byte[] decodedBytes = decodeBinary(bodyBase64);
		String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
		if (decoded.isBlank()) {
			throw new InvalidEncodedBodyException("decoded body must not be blank");
		}
		if (decoded.length() > MAX_BODY_LENGTH) {
			throw new InvalidEncodedBodyException("decoded body must be 1000000 characters or less");
		}
		rejectNul(decoded);
		return decoded;
	}

	public String decodeOptionalBody(String bodyBase64) {
		if (bodyBase64 == null || bodyBase64.isEmpty()) {
			return "";
		}
		byte[] decodedBytes = decodeBinary(bodyBase64);
		String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
		if (decoded.length() > MAX_BODY_LENGTH) {
			throw new InvalidEncodedBodyException("decoded body must be 1000000 characters or less");
		}
		rejectNul(decoded);
		return decoded;
	}

	// PostgreSQL text 컬럼은 NUL을 저장하지 못한다. 저장 단계 오류(409)가 되기 전에 400으로 거부한다.
	private static void rejectNul(String decoded) {
		if (decoded.indexOf('\0') >= 0) {
			throw new InvalidEncodedBodyException("decoded body must not contain NUL characters");
		}
	}

	public byte[] decodeBinary(String bodyBase64) {
		try {
			return Base64.getDecoder().decode(bodyBase64);
		} catch (IllegalArgumentException exception) {
			throw new InvalidEncodedBodyException("bodyBase64 must be valid base64");
		}
	}
}
