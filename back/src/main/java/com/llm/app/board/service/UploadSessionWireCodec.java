package com.llm.app.board.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.board.dto.CreateUploadSessionRequest;
import com.llm.app.board.dto.EncryptedUploadSessionChunkUploadRequest;
import com.llm.app.board.dto.EncryptedUploadSessionCreateRequest;
import com.llm.app.board.dto.UploadSessionStatusResponse;
import com.llm.app.board.exception.InvalidUploadSessionRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UploadSessionWireCodec {
	public static final String A1 = "A1";
	public static final String A2 = "A2";
	public static final String A3 = "A3";
	public static final String A4 = "A4";
	public static final String A5 = "A5";
	public static final String A6 = "A6";
	public static final String A7 = "A7";
	public static final String A8 = "A8";
	public static final String A9 = "A9";
	public static final String A10 = "A10";
	public static final String A11 = "A11";

	private static final byte VERSION = 1;
	private static final int NONCE_LENGTH_BYTES = 12;
	private static final int GCM_TAG_LENGTH_BITS = 128;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final JavaType LIST_OF_INTEGER_TYPE = new ObjectMapper().getTypeFactory()
		.constructCollectionType(List.class, Integer.class);

	private final ObjectMapper objectMapper;
	private final SecretKey secretKey;

	public UploadSessionWireCodec(
		ObjectMapper objectMapper,
		@Value("${app.upload-sessions.secret:defaultUploadSessionsSecretForDevelopmentOnlyMustBeAtLeast256Bits!!}") String secret
	) {
		this.objectMapper = objectMapper;
		this.secretKey = new SecretKeySpec(deriveKeyBytes(secret), "AES");
	}

	public CreateUploadSessionRequest decodeCreateRequest(EncryptedUploadSessionCreateRequest request) {
		return new CreateUploadSessionRequest(
			decryptValue(A1, request.a1(), String.class),
			decryptValue(A2, request.a2(), Long.class),
			decryptValue(A3, request.a3(), Long.class),
			decryptValue(A4, request.a4(), Integer.class),
			decryptValue(A5, request.a5(), String.class)
		);
	}

	public ChunkUploadCommand decodeChunkRequest(EncryptedUploadSessionChunkUploadRequest request) {
		return new ChunkUploadCommand(
			decryptValue(A10, request.a10(), Integer.class),
			decryptValue(A11, request.a11(), String.class)
		);
	}

	public UploadSessionStatusResponse encodeStatus(UploadSessionStatusSnapshot snapshot) {
		return new UploadSessionStatusResponse(
			encryptValue(A6, snapshot.sessionId()),
			encryptValue(A1, snapshot.archiveName()),
			encryptValue(A2, snapshot.fileSizeBytes()),
			encryptValue(A3, snapshot.chunkSizeBase64Chars()),
			encryptValue(A4, snapshot.totalChunks()),
			encryptValue(A7, snapshot.uploadedChunks()),
			encryptValue(A8, snapshot.complete()),
			encryptValue(A9, snapshot.expiresAt())
		);
	}

	public UploadSessionStatusSnapshot decodeStatus(UploadSessionStatusResponse response) {
		return new UploadSessionStatusSnapshot(
			decryptValue(A6, response.sessionId(), UUID.class),
			decryptValue(A1, response.archiveName(), String.class),
			decryptValue(A2, response.fileSizeBytes(), Long.class),
			decryptValue(A3, response.chunkSizeBase64Chars(), Long.class),
			decryptValue(A4, response.totalChunks(), Integer.class),
			decryptValue(A7, response.uploadedChunks(), LIST_OF_INTEGER_TYPE),
			decryptValue(A8, response.complete(), Boolean.class),
			decryptValue(A9, response.expiresAt(), java.time.Instant.class)
		);
	}

	public EncryptedUploadSessionCreateRequest encodeCreateRequest(CreateUploadSessionRequest request) {
		return new EncryptedUploadSessionCreateRequest(
			encryptValue(A1, request.archiveName()),
			encryptValue(A2, request.fileSizeBytes()),
			encryptValue(A3, request.chunkSizeBase64Chars()),
			encryptValue(A4, request.totalChunks()),
			encryptValue(A5, request.fileSha256())
		);
	}

	public EncryptedUploadSessionChunkUploadRequest encodeChunkRequest(int chunkNumber, String chunkDataBase64) {
		return new EncryptedUploadSessionChunkUploadRequest(
			encryptValue(A10, chunkNumber),
			encryptValue(A11, chunkDataBase64)
		);
	}

	public <T> T decryptValue(String alias, String encryptedValue, Class<T> type) {
		try {
			return objectMapper.readValue(decrypt(alias, encryptedValue), type);
		} catch (InvalidUploadSessionRequestException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new InvalidUploadSessionRequestException("failed to decode encrypted upload-session payload");
		}
	}

	public <T> T decryptValue(String alias, String encryptedValue, JavaType type) {
		try {
			return objectMapper.readValue(decrypt(alias, encryptedValue), type);
		} catch (InvalidUploadSessionRequestException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new InvalidUploadSessionRequestException("failed to decode encrypted upload-session payload");
		}
	}

	public String encryptValue(String alias, Object value) {
		try {
			byte[] plaintext = objectMapper.writeValueAsBytes(value);
			byte[] nonce = new byte[NONCE_LENGTH_BYTES];
			SECURE_RANDOM.nextBytes(nonce);

			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
			cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
			byte[] ciphertext = cipher.doFinal(plaintext);

			ByteBuffer buffer = ByteBuffer.allocate(1 + NONCE_LENGTH_BYTES + ciphertext.length);
			buffer.put(VERSION);
			buffer.put(nonce);
			buffer.put(ciphertext);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
		} catch (InvalidUploadSessionRequestException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new InvalidUploadSessionRequestException("failed to encode encrypted upload-session payload");
		}
	}

	private byte[] decrypt(String alias, String encryptedValue) {
		byte[] payload;
		try {
			payload = Base64.getUrlDecoder().decode(encryptedValue);
		} catch (IllegalArgumentException exception) {
			throw new InvalidUploadSessionRequestException("encrypted upload-session payload must be valid base64url");
		}
		if (payload.length < 1 + NONCE_LENGTH_BYTES + 16) {
			throw new InvalidUploadSessionRequestException("encrypted upload-session payload is too short");
		}
		if (payload[0] != VERSION) {
			throw new InvalidUploadSessionRequestException("unsupported encrypted upload-session payload version");
		}

		byte[] nonce = new byte[NONCE_LENGTH_BYTES];
		System.arraycopy(payload, 1, nonce, 0, NONCE_LENGTH_BYTES);
		byte[] ciphertext = new byte[payload.length - 1 - NONCE_LENGTH_BYTES];
		System.arraycopy(payload, 1 + NONCE_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

		try {
			Cipher cipher = cipher(Cipher.DECRYPT_MODE, nonce);
			cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
			return cipher.doFinal(ciphertext);
		} catch (GeneralSecurityException exception) {
			throw new InvalidUploadSessionRequestException("encrypted upload-session payload is invalid");
		}
	}

	private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
		return cipher;
	}

	private byte[] deriveKeyBytes(String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			try {
				bytes = MessageDigest.getInstance("SHA-256").digest(bytes);
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException("SHA-256 not available", exception);
			}
		}
		byte[] keyBytes = new byte[32];
		System.arraycopy(bytes, 0, keyBytes, 0, Math.min(bytes.length, 32));
		return keyBytes;
	}

	public record ChunkUploadCommand(int chunkNumber, String chunkDataBase64) {
	}

}
