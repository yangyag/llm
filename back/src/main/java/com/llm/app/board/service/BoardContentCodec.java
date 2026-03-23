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
		return decoded;
	}

	public byte[] decodeBinary(String bodyBase64) {
		try {
			return Base64.getDecoder().decode(bodyBase64);
		} catch (IllegalArgumentException exception) {
			throw new InvalidEncodedBodyException("bodyBase64 must be valid base64");
		}
	}
}
