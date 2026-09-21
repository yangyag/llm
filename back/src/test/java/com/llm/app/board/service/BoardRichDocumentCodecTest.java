package com.llm.app.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.app.board.exception.InvalidRichDocumentException;
import com.llm.app.board.model.PostBodyFormat;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoardRichDocumentCodecTest {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final BoardRichDocumentCodec codec = new BoardRichDocumentCodec(objectMapper);

	private String encode(String json) {
		return encode(json.getBytes(StandardCharsets.UTF_8));
	}

	private String encode(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private void assertInvalid(String document) {
		assertThatThrownBy(() -> codec.decode(encode(document)))
			.isInstanceOf(InvalidRichDocumentException.class);
	}

	@Test
	void shouldCanonicalizeComplexDocumentAndExtractPlainText() {
		String document = """
			{
			  "type": "doc",
			  "content": [
			    {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "제목", "marks": [{"type": "italic"}, {"type": "bold"}]}]},
			    {"type": "paragraph", "content": [{"type": "text", "text": "첫째"}, {"type": "hardBreak"}, {"type": "text", "text": "둘째", "marks": [{"type": "code"}]}]},
			    {"type": "blockquote", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "인용"}]}]},
			    {"type": "bulletList", "content": [
			      {"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "항목1"}]}]},
			      {"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "항목2"}]}, {"type": "paragraph", "content": [{"type": "text", "text": "둘째줄"}]}]}
			    ]},
			    {"type": "orderedList", "attrs": {"start": 3}, "content": [
			      {"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "가"}]}]},
			      {"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "나"}]}]}
			    ]},
			    {"type": "codeBlock", "attrs": {"language": "java"}, "content": [{"type": "text", "text": "code"}]},
			    {"type": "horizontalRule"},
			    {"type": "inlineAttachmentImage", "attrs": {"imageKey": "B1E09B73-1111-4444-8888-123456789ABC", "alt": "사진"}},
			    {"type": "paragraph", "content": [{"type": "text", "text": "마지막"}]}
			  ]
			}
			""";

		BoardRichDocumentCodec.DecodedDocument decoded = codec.decode(encode(document));

		assertThat(decoded.plainText()).isEqualTo("제목\n첫째\n둘째\n인용\n- 항목1\n- 항목2\n둘째줄\n3. 가\n4. 나\ncode\n[구분선]\n[이미지: 사진]\n마지막");
		assertThat(decoded.imageKeys()).containsExactly(UUID.fromString("b1e09b73-1111-4444-8888-123456789abc"));
		assertThat(decoded.canonicalJson()).isEqualTo(
			"{\"type\":\"doc\",\"content\":["
				+ "{\"type\":\"heading\",\"content\":[{\"type\":\"text\",\"text\":\"제목\",\"marks\":[{\"type\":\"bold\"},{\"type\":\"italic\"}]}],\"attrs\":{\"level\":2}},"
				+ "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"첫째\"},{\"type\":\"hardBreak\"},{\"type\":\"text\",\"text\":\"둘째\",\"marks\":[{\"type\":\"code\"}]}]},"
				+ "{\"type\":\"blockquote\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"인용\"}]}]},"
				+ "{\"type\":\"bulletList\",\"content\":["
				+ "{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"항목1\"}]}]},"
				+ "{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"항목2\"}]},{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"둘째줄\"}]}]}]},"
				+ "{\"type\":\"orderedList\",\"content\":["
				+ "{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"가\"}]}]},"
				+ "{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"나\"}]}]}],\"attrs\":{\"start\":3,\"type\":null}},"
				+ "{\"type\":\"codeBlock\",\"content\":[{\"type\":\"text\",\"text\":\"code\"}],\"attrs\":{\"language\":\"java\"}},"
				+ "{\"type\":\"horizontalRule\"},"
				+ "{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"b1e09b73-1111-4444-8888-123456789abc\",\"alt\":\"사진\"}},"
				+ "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"마지막\"}]}]}"
		);
	}

	@Test
	void shouldAcceptEmptyDocument() {
		BoardRichDocumentCodec.DecodedDocument decoded = codec.decode(encode("{\"type\":\"doc\"}"));

		assertThat(decoded.canonicalJson()).isEqualTo("{\"type\":\"doc\",\"content\":[]}");
		assertThat(decoded.plainText()).isEmpty();
		assertThat(decoded.imageKeys()).isEmpty();
	}

	@Test
	void shouldRejectUndecodableOrNonJsonInput() {
		assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(InvalidRichDocumentException.class);
		assertThatThrownBy(() -> codec.decode("")).isInstanceOf(InvalidRichDocumentException.class);
		assertThatThrownBy(() -> codec.decode("%%%not-base64%%%")).isInstanceOf(InvalidRichDocumentException.class);
		assertThatThrownBy(() -> codec.decode(encode(new byte[] { (byte) 0xC3, 0x28 })))
			.isInstanceOf(InvalidRichDocumentException.class);
		assertInvalid("{not json");
		assertInvalid("[1,2,3]");
		assertInvalid("\"plain string\"");
		assertInvalid("{\"type\":\"paragraph\"}");
		assertInvalid("{\"content\":[]}");
	}

	@Test
	void shouldRejectUnknownNodesFieldsAndMarks() {
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"video\"}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"html\",\"content\":\"<b>x</b>\"}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"html\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"foo\":1}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"horizontalRule\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"align\":\"left\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\"}]}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"bold\",\"attrs\":{}}]}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"bold\"},{\"type\":\"bold\"}]}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"a\u0000b\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"bold\"}]},\"attrs\":null}]}");
	}

	@Test
	void shouldEnforceNodeSchemas() {
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":7},\"content\":[]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":\"2\"},\"content\":[]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":2,\"extra\":true},\"content\":[]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"blockquote\",\"content\":[]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"blockquote\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"bulletList\",\"content\":[]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"bulletList\",\"content\":[{\"type\":\"paragraph\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"bulletList\",\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":1}}]}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"orderedList\",\"attrs\":{\"start\":0},\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\"}]}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"orderedList\",\"attrs\":{\"start\":1000001},\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\"}]}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"orderedList\",\"attrs\":{\"type\":\"1\"},\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\"}]}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"codeBlock\",\"content\":[{\"type\":\"hardBreak\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"codeBlock\",\"attrs\":{\"language\":\"bad lang!\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"horizontalRule\",\"content\":[]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"doc\"}]}]}");
	}

	@Test
	void shouldValidateInlineAttachmentImageNodes() {
		String key = "b1e09b73-1111-4444-8888-123456789abc";
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"not-a-uuid\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"1-1-1-1-1\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\",\"src\":\"https://evil.example/x.png\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"alt\":\"no key\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\",\"alt\":\"" + "a".repeat(201) + "\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\"}},{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\"}}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\"}]}");

		BoardRichDocumentCodec.DecodedDocument decoded = codec.decode(encode(
			"{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\"}}]}"));
		assertThat(decoded.plainText()).isEqualTo("[이미지]");
		assertThat(decoded.imageKeys()).containsExactly(UUID.fromString(key));

		BoardRichDocumentCodec.DecodedDocument withAlt = codec.decode(encode(
			"{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\",\"alt\":\"" + "a".repeat(200) + "\"}}]}"));
		assertThat(withAlt.plainText()).isEqualTo("[이미지: " + "a".repeat(200) + "]");
	}

	@Test
	void shouldRejectInlineImageInjectionFieldsAndKeepAltAsPlainText() {
		String key = "b1e09b73-1111-4444-8888-123456789abc";
		String image = "\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\"";
		String docStart = "{\"type\":\"doc\",\"content\":[{";
		assertInvalid(docStart + image + ",\"src\":\"data:image/png;base64,AAAA\"}}]}");
		assertInvalid(docStart + image + ",\"src\":\"javascript:alert(1)\"}}]}");
		assertInvalid(docStart + image + ",\"style\":\"position:fixed;inset:0\"}}]}");
		assertInvalid(docStart + image + ",\"onerror\":\"alert(1)\"}}]}");
		assertInvalid(docStart + "\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\"},\"html\":\"<img src=x onerror=alert(1)>\"}]}");
		assertInvalid(docStart + "\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"" + key + "\"},\"onclick\":\"alert(1)\"}]}");
		assertInvalid(docStart + image + ",\"alt\":\"bad\\u0000alt\"}}]}");
		assertInvalid(docStart + image + ",\"alt\":\"bad\\uD800\"}}]}");

		String markupAlt = "<img src=x onerror=alert(1)>";
		BoardRichDocumentCodec.DecodedDocument decoded = codec.decode(encode(
			docStart + image + ",\"alt\":\"" + markupAlt + "\"}}]}"));
		assertThat(decoded.imageKeys()).containsExactly(UUID.fromString(key));
		assertThat(decoded.plainText()).isEqualTo("[이미지: " + markupAlt + "]");
		assertThat(decoded.canonicalJson()).contains("\"alt\":\"" + markupAlt + "\"");
	}

	@Test
	void shouldEnforceDecodedDocumentByteLimit() {
		byte[] document = "{\"type\":\"doc\",\"content\":[]}".getBytes(StandardCharsets.UTF_8);

		byte[] belowLimit = new byte[BoardRichDocumentCodec.MAX_DOCUMENT_BYTES - 1];
		System.arraycopy(document, 0, belowLimit, 0, document.length);
		java.util.Arrays.fill(belowLimit, document.length, belowLimit.length, (byte) ' ');
		assertThat(codec.decode(encode(belowLimit)).plainText()).isEmpty();

		byte[] atLimit = new byte[BoardRichDocumentCodec.MAX_DOCUMENT_BYTES];
		System.arraycopy(document, 0, atLimit, 0, document.length);
		java.util.Arrays.fill(atLimit, document.length, atLimit.length, (byte) ' ');
		assertThat(codec.decode(encode(atLimit)).plainText()).isEmpty();

		byte[] overLimit = new byte[BoardRichDocumentCodec.MAX_DOCUMENT_BYTES + 1];
		System.arraycopy(document, 0, overLimit, 0, document.length);
		java.util.Arrays.fill(overLimit, document.length, overLimit.length, (byte) ' ');
		assertThatThrownBy(() -> codec.decode(encode(overLimit))).isInstanceOf(InvalidRichDocumentException.class);
	}

	@Test
	void shouldEnforcePlainTextLengthLimit() {
		String atLimit = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
			+ "a".repeat(BoardRichDocumentCodec.MAX_PLAIN_TEXT_LENGTH) + "\"}]}]}";
		assertThat(codec.decode(encode(atLimit)).plainText()).hasSize(BoardRichDocumentCodec.MAX_PLAIN_TEXT_LENGTH);

		String overLimit = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
			+ "a".repeat(BoardRichDocumentCodec.MAX_PLAIN_TEXT_LENGTH + 1) + "\"}]}]}";
		assertThatThrownBy(() -> codec.decode(encode(overLimit))).isInstanceOf(InvalidRichDocumentException.class);
	}

	@Test
	void shouldEnforceNodeCountLimit() {
		assertThat(codec.decode(encode(documentWithHorizontalRules(BoardRichDocumentCodec.MAX_NODES - 1))).imageKeys())
			.isEmpty();
		assertThatThrownBy(() -> codec.decode(encode(documentWithHorizontalRules(BoardRichDocumentCodec.MAX_NODES))))
			.isInstanceOf(InvalidRichDocumentException.class);
	}

	private String documentWithHorizontalRules(int ruleCount) {
		StringBuilder builder = new StringBuilder("{\"type\":\"doc\",\"content\":[");
		for (int index = 0; index < ruleCount; index++) {
			if (index > 0) {
				builder.append(',');
			}
			builder.append("{\"type\":\"horizontalRule\"}");
		}
		return builder.append("]}").toString();
	}

	@Test
	void shouldEnforceDepthLimit() {
		assertThat(codec.decode(encode(documentWithBlockquoteDepth(BoardRichDocumentCodec.MAX_DEPTH - 2))).plainText())
			.isEmpty();
		assertThatThrownBy(() -> codec.decode(encode(documentWithBlockquoteDepth(BoardRichDocumentCodec.MAX_DEPTH - 1))))
			.isInstanceOf(InvalidRichDocumentException.class);
	}

	private String documentWithBlockquoteDepth(int blockquoteCount) {
		StringBuilder builder = new StringBuilder("{\"type\":\"doc\",\"content\":[");
		builder.append("{\"type\":\"blockquote\",\"content\":[".repeat(blockquoteCount));
		builder.append("{\"type\":\"paragraph\"}");
		builder.append("]}".repeat(blockquoteCount));
		return builder.append("]}").toString();
	}

	@Test
	void shouldReadStoredDocuments() throws Exception {
		assertThat(codec.readStoredDocument(PostBodyFormat.PLAIN_TEXT, null)).isNull();
		String canonical = "{\"type\":\"doc\",\"content\":[]}";
		JsonNode stored = codec.readStoredDocument(PostBodyFormat.TIPTAP_JSON, canonical);
		assertThat(stored.get("type").asText()).isEqualTo("doc");

		assertThatThrownBy(() -> codec.readStoredDocument(PostBodyFormat.PLAIN_TEXT, canonical))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> codec.readStoredDocument(PostBodyFormat.TIPTAP_JSON, null))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> codec.readStoredDocument(null, canonical))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> codec.readStoredDocument(PostBodyFormat.TIPTAP_JSON, "{broken"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> codec.readStoredDocument(PostBodyFormat.TIPTAP_JSON, "[]"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> codec.readStoredDocument(PostBodyFormat.TIPTAP_JSON, "\"text\""))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> codec.readStoredDocument(
				PostBodyFormat.TIPTAP_JSON, "{\"type\":\"doc\",\"content\":[{\"type\":\"video\"}]}"))
			.isInstanceOf(IllegalStateException.class);

		JsonNode normalized = codec.readStoredDocument(PostBodyFormat.TIPTAP_JSON, "{\"type\":\"doc\"}");
		assertThat(normalized.isObject()).isTrue();
		assertThat(normalized.get("type").asText()).isEqualTo("doc");
		assertThat(normalized.get("content").isArray()).isTrue();
		assertThat(normalized.get("content").size()).isZero();
	}

	@Test
	void shouldRejectLoneSurrogatesAndAllowValidPairs() {
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"a\\uD800b\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"a\\uDC00b\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"a\\uD800\"}]}]}");
		assertInvalid("{\"type\":\"doc\",\"content\":[{\"type\":\"inlineAttachmentImage\",\"attrs\":{\"imageKey\":\"b1e09b73-1111-4444-8888-123456789abc\",\"alt\":\"bad\\uD800\"}}]}");

		BoardRichDocumentCodec.DecodedDocument decoded = codec.decode(encode(
			"{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"ok\\uD83D\\uDE00\"}]}]}"));
		assertThat(decoded.plainText()).isEqualTo("ok😀");
	}
}
