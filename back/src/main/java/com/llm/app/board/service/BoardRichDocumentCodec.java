package com.llm.app.board.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llm.app.board.exception.InvalidRichDocumentException;
import com.llm.app.board.model.PostBodyFormat;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class BoardRichDocumentCodec {
	public static final int MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
	public static final int MAX_PLAIN_TEXT_LENGTH = 1_000_000;
	public static final int MAX_NODES = 20_000;
	public static final int MAX_DEPTH = 20;
	public static final int MAX_ALT_LENGTH = 200;

	private static final Set<String> TOP_LEVEL_BLOCKS = Set.of(
		"paragraph", "heading", "blockquote", "bulletList", "orderedList", "codeBlock",
		"horizontalRule", "inlineAttachmentImage"
	);
	private static final Set<String> INLINE_NODES = Set.of("text", "hardBreak");
	private static final Set<String> LIST_ITEM_BLOCKS = Set.of(
		"paragraph", "blockquote", "bulletList", "orderedList", "codeBlock",
		"horizontalRule", "inlineAttachmentImage"
	);
	private static final Set<String> ALLOWED_MARKS = Set.of("bold", "italic", "strike", "underline", "code");
	private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[A-Za-z0-9_+.#-]{1,50}");
	private static final Pattern UUID_PATTERN = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
	);

	private final ObjectMapper objectMapper;

	public BoardRichDocumentCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public DecodedDocument decode(String bodyDocumentBase64) {
		if (bodyDocumentBase64 == null || bodyDocumentBase64.isEmpty()) {
			throw new InvalidRichDocumentException("body document must not be empty");
		}
		if (bodyDocumentBase64.length() > (MAX_DOCUMENT_BYTES / 3 + 2) * 4) {
			throw new InvalidRichDocumentException("body document exceeds the 5 MiB limit");
		}
		byte[] decodedBytes;
		try {
			decodedBytes = Base64.getDecoder().decode(bodyDocumentBase64);
		} catch (IllegalArgumentException exception) {
			throw new InvalidRichDocumentException("bodyDocumentBase64 must be valid base64");
		}
		if (decodedBytes.length > MAX_DOCUMENT_BYTES) {
			throw new InvalidRichDocumentException("body document exceeds the 5 MiB limit");
		}
		String json = decodeUtf8(decodedBytes);
		JsonNode root;
		try {
			root = objectMapper.readTree(json);
		} catch (Exception exception) {
			throw new InvalidRichDocumentException("body document must be valid JSON");
		}
		if (root == null || !root.isObject() || !"doc".equals(readType(root))) {
			throw new InvalidRichDocumentException("body document must be a doc object");
		}
		Walker walker = new Walker();
		ObjectNode canonical = canonicalize(root, 1, walker);
		String canonicalJson;
		try {
			canonicalJson = objectMapper.writeValueAsString(canonical);
		} catch (Exception exception) {
			throw new IllegalStateException("failed to serialize canonical document", exception);
		}
		String plainText = plainText(canonical);
		if (plainText.length() > MAX_PLAIN_TEXT_LENGTH) {
			throw new InvalidRichDocumentException("extracted plain text exceeds the maximum length");
		}
		return new DecodedDocument(canonicalJson, plainText, walker.imageKeys);
	}

	public JsonNode readStoredDocument(PostBodyFormat format, String bodyDocument) {
		if (format == null) {
			throw new IllegalStateException("stored post body document is invalid");
		}
		return switch (format) {
			case PLAIN_TEXT -> {
				if (bodyDocument != null) {
					throw new IllegalStateException("stored post body document is invalid");
				}
				yield null;
			}
			case TIPTAP_JSON -> {
				if (bodyDocument == null) {
					throw new IllegalStateException("stored post body document is invalid");
				}
				yield readStoredRichDocument(bodyDocument);
			}
		};
	}

	private JsonNode readStoredRichDocument(String bodyDocument) {
		try {
			if (bodyDocument.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
				throw new IllegalStateException();
			}
			JsonNode root = objectMapper.readTree(bodyDocument);
			if (root == null || !root.isObject() || !"doc".equals(readType(root))) {
				throw new IllegalStateException();
			}
			Walker walker = new Walker();
			ObjectNode canonical = canonicalize(root, 1, walker);
			String plain = plainText(canonical);
			if (plain.length() > MAX_PLAIN_TEXT_LENGTH) {
				throw new IllegalStateException();
			}
			return canonical;
		} catch (Exception exception) {
			throw new IllegalStateException("stored post body document is invalid");
		}
	}

	private String decodeUtf8(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException exception) {
			throw new InvalidRichDocumentException("body document must be valid UTF-8");
		}
	}

	private ObjectNode canonicalize(JsonNode node, int depth, Walker walker) {
		if (depth > MAX_DEPTH) {
			throw new InvalidRichDocumentException("body document exceeds the maximum depth");
		}
		String type = readType(node);
		walker.nodeCount++;
		if (walker.nodeCount > MAX_NODES) {
			throw new InvalidRichDocumentException("body document exceeds the maximum node count");
		}
		return switch (type) {
			case "doc" -> canonicalDoc(node, depth, walker);
			case "paragraph" -> canonicalInlineContainer(node, depth, walker, "paragraph", Set.of("type", "content"));
			case "heading" -> canonicalHeading(node, depth, walker);
			case "blockquote" -> canonicalRequiredContainer(node, depth, walker, "blockquote", TOP_LEVEL_BLOCKS);
			case "bulletList" -> canonicalRequiredContainer(node, depth, walker, "bulletList", Set.of("listItem"));
			case "orderedList" -> canonicalOrderedList(node, depth, walker);
			case "listItem" -> canonicalListItem(node, depth, walker);
			case "codeBlock" -> canonicalCodeBlock(node, depth, walker);
			case "horizontalRule" -> canonicalLeaf(node, "horizontalRule");
			case "inlineAttachmentImage" -> canonicalImage(node, walker);
			case "hardBreak" -> canonicalLeaf(node, "hardBreak");
			case "text" -> canonicalText(node);
			default -> throw new InvalidRichDocumentException("unsupported node type");
		};
	}

	private ObjectNode canonicalDoc(JsonNode node, int depth, Walker walker) {
		requireOnlyFields(node, Set.of("type", "content"));
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", "doc");
		canonical.set("content", canonicalChildren(node, depth, walker, TOP_LEVEL_BLOCKS, false));
		return canonical;
	}

	private ObjectNode canonicalInlineContainer(
		JsonNode node,
		int depth,
		Walker walker,
		String type,
		Set<String> allowedFields
	) {
		requireOnlyFields(node, allowedFields);
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", type);
		ArrayNode content = canonicalChildren(node, depth, walker, INLINE_NODES, false);
		if (!content.isEmpty()) {
			canonical.set("content", content);
		}
		return canonical;
	}

	private ObjectNode canonicalHeading(JsonNode node, int depth, Walker walker) {
		requireOnlyFields(node, Set.of("type", "content", "attrs"));
		JsonNode attrs = node.get("attrs");
		if (attrs == null || !attrs.isObject()) {
			throw new InvalidRichDocumentException("heading requires an attrs object");
		}
		requireOnlyFields(attrs, Set.of("level"));
		JsonNode level = attrs.get("level");
		if (level == null || !level.isIntegralNumber() || level.longValue() < 1 || level.longValue() > 6) {
			throw new InvalidRichDocumentException("heading level must be an integer between 1 and 6");
		}
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", "heading");
		ArrayNode content = canonicalChildren(node, depth, walker, INLINE_NODES, false);
		if (!content.isEmpty()) {
			canonical.set("content", content);
		}
		canonical.putObject("attrs").put("level", level.intValue());
		return canonical;
	}

	private ObjectNode canonicalRequiredContainer(
		JsonNode node,
		int depth,
		Walker walker,
		String type,
		Set<String> allowedChildTypes
	) {
		requireOnlyFields(node, Set.of("type", "content"));
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", type);
		canonical.set("content", canonicalChildren(node, depth, walker, allowedChildTypes, true));
		return canonical;
	}

	private ObjectNode canonicalOrderedList(JsonNode node, int depth, Walker walker) {
		requireOnlyFields(node, Set.of("type", "content", "attrs"));
		long start = 1;
		JsonNode attrs = node.get("attrs");
		if (attrs != null && !attrs.isNull()) {
			if (!attrs.isObject()) {
				throw new InvalidRichDocumentException("orderedList attrs must be an object");
			}
			requireOnlyFields(attrs, Set.of("start", "type"));
			JsonNode startNode = attrs.get("start");
			if (startNode != null) {
				if (!startNode.isIntegralNumber() || startNode.longValue() < 1 || startNode.longValue() > 1_000_000) {
					throw new InvalidRichDocumentException("orderedList start must be an integer between 1 and 1000000");
				}
				start = startNode.longValue();
			}
			JsonNode typeNode = attrs.get("type");
			if (typeNode != null && !typeNode.isNull()) {
				throw new InvalidRichDocumentException("orderedList type attribute is not supported");
			}
		}
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", "orderedList");
		canonical.set("content", canonicalChildren(node, depth, walker, Set.of("listItem"), true));
		ObjectNode canonicalAttrs = canonical.putObject("attrs");
		canonicalAttrs.put("start", start);
		canonicalAttrs.putNull("type");
		return canonical;
	}

	private ObjectNode canonicalListItem(JsonNode node, int depth, Walker walker) {
		requireOnlyFields(node, Set.of("type", "content"));
		JsonNode content = node.get("content");
		if (content == null || !content.isArray() || content.isEmpty()) {
			throw new InvalidRichDocumentException("listItem requires a non-empty content array");
		}
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", "listItem");
		ArrayNode canonicalContent = objectMapper.createArrayNode();
		for (int index = 0; index < content.size(); index++) {
			JsonNode child = content.get(index);
			String childType = readType(child);
			boolean allowed = index == 0 ? "paragraph".equals(childType) : LIST_ITEM_BLOCKS.contains(childType);
			if (!allowed) {
				throw new InvalidRichDocumentException("unsupported listItem child node type");
			}
			canonicalContent.add(canonicalize(child, depth + 1, walker));
		}
		canonical.set("content", canonicalContent);
		return canonical;
	}

	private ObjectNode canonicalCodeBlock(JsonNode node, int depth, Walker walker) {
		requireOnlyFields(node, Set.of("type", "content", "attrs"));
		String language = null;
		JsonNode attrs = node.get("attrs");
		if (attrs != null && !attrs.isNull()) {
			if (!attrs.isObject()) {
				throw new InvalidRichDocumentException("codeBlock attrs must be an object");
			}
			requireOnlyFields(attrs, Set.of("language"));
			JsonNode languageNode = attrs.get("language");
			if (languageNode != null && !languageNode.isNull()) {
				if (!languageNode.isTextual()
					|| !LANGUAGE_PATTERN.matcher(languageNode.asText()).matches()
					|| containsInvalidCharacter(languageNode.asText())) {
					throw new InvalidRichDocumentException("codeBlock language is invalid");
				}
				language = languageNode.asText();
			}
		}
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", "codeBlock");
		ArrayNode content = canonicalChildren(node, depth, walker, Set.of("text"), false);
		if (!content.isEmpty()) {
			canonical.set("content", content);
		}
		ObjectNode canonicalAttrs = canonical.putObject("attrs");
		if (language == null) {
			canonicalAttrs.putNull("language");
		} else {
			canonicalAttrs.put("language", language);
		}
		return canonical;
	}

	private ObjectNode canonicalLeaf(JsonNode node, String type) {
		requireOnlyFields(node, Set.of("type"));
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", type);
		return canonical;
	}

	private ObjectNode canonicalImage(JsonNode node, Walker walker) {
		requireOnlyFields(node, Set.of("type", "attrs"));
		JsonNode attrs = node.get("attrs");
		if (attrs == null || !attrs.isObject()) {
			throw new InvalidRichDocumentException("inline image requires an attrs object");
		}
		requireOnlyFields(attrs, Set.of("imageKey", "alt"));
		JsonNode imageKeyNode = attrs.get("imageKey");
		if (imageKeyNode == null || !imageKeyNode.isTextual()
			|| !UUID_PATTERN.matcher(imageKeyNode.asText()).matches()) {
			throw new InvalidRichDocumentException("inline image key must be a UUID");
		}
		UUID imageKey = UUID.fromString(imageKeyNode.asText());
		if (!walker.imageKeys.add(imageKey)) {
			throw new InvalidRichDocumentException("duplicate inline image key");
		}
		String alt = "";
		JsonNode altNode = attrs.get("alt");
		if (altNode != null && !altNode.isNull()) {
			if (!altNode.isTextual() || altNode.asText().length() > MAX_ALT_LENGTH || containsInvalidCharacter(altNode.asText())) {
				throw new InvalidRichDocumentException("inline image alt is invalid");
			}
			alt = altNode.asText();
		}
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", "inlineAttachmentImage");
		ObjectNode canonicalAttrs = canonical.putObject("attrs");
		canonicalAttrs.put("imageKey", imageKey.toString());
		canonicalAttrs.put("alt", alt);
		return canonical;
	}

	private ObjectNode canonicalText(JsonNode node) {
		requireOnlyFields(node, Set.of("type", "text", "marks"));
		JsonNode textNode = node.get("text");
		if (textNode == null || !textNode.isTextual() || textNode.asText().isEmpty() || containsInvalidCharacter(textNode.asText())) {
			throw new InvalidRichDocumentException("text node requires a non-empty text");
		}
		List<String> markTypes = canonicalMarkTypes(node);
		ObjectNode canonical = objectMapper.createObjectNode();
		canonical.put("type", "text");
		canonical.put("text", textNode.asText());
		if (!markTypes.isEmpty()) {
			ArrayNode marks = canonical.putArray("marks");
			for (String markType : markTypes) {
				marks.addObject().put("type", markType);
			}
		}
		return canonical;
	}

	private List<String> canonicalMarkTypes(JsonNode node) {
		JsonNode marks = node.get("marks");
		if (marks == null || marks.isNull()) {
			return List.of();
		}
		if (!marks.isArray()) {
			throw new InvalidRichDocumentException("marks must be an array");
		}
		Set<String> markTypes = new TreeSet<>();
		for (JsonNode mark : marks) {
			if (!mark.isObject()) {
				throw new InvalidRichDocumentException("mark must be an object");
			}
			requireOnlyFields(mark, Set.of("type"));
			JsonNode markType = mark.get("type");
			if (markType == null || !markType.isTextual() || !ALLOWED_MARKS.contains(markType.asText())) {
				throw new InvalidRichDocumentException("unsupported mark type");
			}
			if (!markTypes.add(markType.asText())) {
				throw new InvalidRichDocumentException("duplicate mark type");
			}
		}
		return new ArrayList<>(markTypes);
	}

	private ArrayNode canonicalChildren(
		JsonNode node,
		int depth,
		Walker walker,
		Set<String> allowedChildTypes,
		boolean required
	) {
		JsonNode content = node.get("content");
		if (content == null || content.isNull()) {
			if (required) {
				throw new InvalidRichDocumentException("node requires a non-empty content array");
			}
			return objectMapper.createArrayNode();
		}
		if (!content.isArray()) {
			throw new InvalidRichDocumentException("content must be an array");
		}
		ArrayNode canonicalContent = objectMapper.createArrayNode();
		for (JsonNode child : content) {
			String childType = readType(child);
			if (!allowedChildTypes.contains(childType)) {
				throw new InvalidRichDocumentException("unsupported child node type");
			}
			canonicalContent.add(canonicalize(child, depth + 1, walker));
		}
		if (required && canonicalContent.isEmpty()) {
			throw new InvalidRichDocumentException("node requires a non-empty content array");
		}
		return canonicalContent;
	}

	private String readType(JsonNode node) {
		if (node == null || !node.isObject()) {
			throw new InvalidRichDocumentException("each node must be an object");
		}
		JsonNode typeNode = node.get("type");
		if (typeNode == null || !typeNode.isTextual()) {
			throw new InvalidRichDocumentException("each node requires a type");
		}
		return typeNode.asText();
	}

	private void requireOnlyFields(JsonNode node, Set<String> allowedFields) {
		Iterator<String> fieldNames = node.fieldNames();
		while (fieldNames.hasNext()) {
			if (!allowedFields.contains(fieldNames.next())) {
				throw new InvalidRichDocumentException("node contains an unsupported field");
			}
		}
	}

	private boolean containsInvalidCharacter(String value) {
		for (int index = 0; index < value.length(); index++) {
			char unit = value.charAt(index);
			if (unit == 0) {
				return true;
			}
			if (Character.isHighSurrogate(unit)) {
				if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
					return true;
				}
				index++;
			} else if (Character.isLowSurrogate(unit)) {
				return true;
			}
		}
		return false;
	}

	private String plainText(JsonNode node) {
		String type = node.get("type").asText();
		JsonNode content = node.get("content");
		return switch (type) {
			case "doc", "blockquote", "listItem" -> joinPlain(content, "\n");
			case "paragraph", "heading", "codeBlock" -> joinPlain(content, "");
			case "text" -> node.get("text").asText();
			case "hardBreak" -> "\n";
			case "horizontalRule" -> "[구분선]";
			case "inlineAttachmentImage" -> {
				String alt = node.get("attrs").get("alt").asText();
				yield alt.isBlank() ? "[이미지]" : "[이미지: " + alt + "]";
			}
			case "bulletList", "orderedList" -> {
				StringBuilder builder = new StringBuilder();
				long start = "orderedList".equals(type) ? node.get("attrs").get("start").asLong() : 1;
				for (int index = 0; index < content.size(); index++) {
					if (index > 0) {
						builder.append('\n');
					}
					String prefix = "orderedList".equals(type) ? (start + index) + ". " : "- ";
					builder.append(prefix).append(plainText(content.get(index)));
				}
				yield builder.toString();
			}
			default -> throw new IllegalStateException("unexpected canonical node type");
		};
	}

	private String joinPlain(JsonNode content, String separator) {
		if (content == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < content.size(); index++) {
			if (index > 0) {
				builder.append(separator);
			}
			builder.append(plainText(content.get(index)));
		}
		return builder.toString();
	}

	private static final class Walker {
		private int nodeCount;
		private final Set<UUID> imageKeys = new LinkedHashSet<>();
	}

	public record DecodedDocument(String canonicalJson, String plainText, Set<UUID> imageKeys) {
		public DecodedDocument {
			imageKeys = Set.copyOf(imageKeys);
		}
	}
}
