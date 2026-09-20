package com.llm.app.board.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.board.exception.InvalidAttachmentRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class InlineImageManifestCodec {
	public static final int MAX_MANIFEST_BYTES = 16 * 1024;

	private static final Pattern UUID_PATTERN = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
	);

	private final ObjectMapper objectMapper;

	public InlineImageManifestCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<Entry> decode(String manifestBase64) {
		if (manifestBase64 == null || manifestBase64.isEmpty()) {
			throw invalid("inline image manifest must not be empty");
		}
		if (manifestBase64.length() > (MAX_MANIFEST_BYTES / 3 + 2) * 4) {
			throw invalid("inline image manifest exceeds the size limit");
		}
		byte[] decodedBytes;
		try {
			decodedBytes = Base64.getDecoder().decode(manifestBase64);
		} catch (IllegalArgumentException exception) {
			throw invalid("inline image manifest must be valid base64");
		}
		if (decodedBytes.length > MAX_MANIFEST_BYTES) {
			throw invalid("inline image manifest exceeds the size limit");
		}
		String json = decodeUtf8(decodedBytes);
		JsonNode root;
		try {
			root = objectMapper.readTree(json);
		} catch (Exception exception) {
			throw invalid("inline image manifest must be valid JSON");
		}
		if (root == null || !root.isArray()) {
			throw invalid("inline image manifest must be an array");
		}
		List<Entry> entries = new ArrayList<>();
		Set<UUID> keys = new HashSet<>();
		Set<Integer> indexes = new HashSet<>();
		for (JsonNode node : root) {
			if (node == null || !node.isObject()) {
				throw invalid("inline image manifest entries must be objects");
			}
			Iterator<String> fieldNames = node.fieldNames();
			while (fieldNames.hasNext()) {
				String field = fieldNames.next();
				if (!"imageKey".equals(field) && !"fileIndex".equals(field)) {
					throw invalid("inline image manifest contains an unsupported field");
				}
			}
			JsonNode keyNode = node.get("imageKey");
			if (keyNode == null || !keyNode.isTextual()
				|| !UUID_PATTERN.matcher(keyNode.asText()).matches()) {
				throw invalid("inline image key must be a UUID");
			}
			JsonNode indexNode = node.get("fileIndex");
			if (indexNode == null || !indexNode.isInt() || indexNode.intValue() < 0) {
				throw invalid("inline image file index must be a non-negative integer");
			}
			UUID key = UUID.fromString(keyNode.asText());
			int index = indexNode.intValue();
			if (!keys.add(key) || !indexes.add(index)) {
				throw invalid("inline image manifest contains duplicates");
			}
			entries.add(new Entry(key, index));
		}
		for (int i = 0; i < entries.size(); i++) {
			if (!indexes.contains(i)) {
				throw invalid("inline image file indexes must be contiguous");
			}
		}
		return List.copyOf(entries.stream().sorted(Comparator.comparingInt(Entry::fileIndex)).toList());
	}

	private String decodeUtf8(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException exception) {
			throw invalid("inline image manifest must be valid UTF-8");
		}
	}

	private InvalidAttachmentRequestException invalid(String message) {
		return new InvalidAttachmentRequestException(message);
	}

	public record Entry(UUID imageKey, int fileIndex) {
	}
}
