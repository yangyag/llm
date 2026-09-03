package com.llm.app.board.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.llm.app.board.exception.AiProviderNotConfiguredException;
import com.llm.app.board.exception.AiReplyGenerationException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
/**
 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 데이터 조회/보호용으로만 유지되며 신규 호출 금지.
 */
@Deprecated
public class ExternalAiReplyGenerator implements AiReplyGenerator {
	private static final String ANTHROPIC_VERSION = "2023-06-01";
	private static final String SYSTEM_PROMPT =
		"너는 익명 게시판의 AI 답변자다. 게시글을 읽고 그 내용을 직접 수행하거나 답해라. "
			+ "예를 들어 '자기 소개'라는 글이면 네가 직접 자기소개를 하고, 질문이면 답을 주고, 요청이면 실행해라. "
			+ "절대 '어떻게 하라'는 식의 조언이나 충고를 하지 마라. "
			+ "한국어로 간결하게 답하고, 인사말이나 마크다운 제목 없이 본문만 평문으로 작성해라.";

	private final RestClient restClient;
	private final String openAiApiKey;
	private final String openAiModel;
	private final String openAiBaseUrl;
	private final String anthropicApiKey;
	private final String anthropicModel;
	private final String anthropicBaseUrl;
	private final String xAiApiKey;
	private final String xAiModel;
	private final String xAiBaseUrl;

	public ExternalAiReplyGenerator(
		@Value("${OPENAI_API_KEY:}") String openAiApiKey,
		@Value("${OPENAI_MODEL:gpt-5.4}") String openAiModel,
		@Value("${OPENAI_API_BASE_URL:https://api.openai.com/v1}") String openAiBaseUrl,
		@Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
		@Value("${ANTHROPIC_MODEL:claude-sonnet-4-6}") String anthropicModel,
		@Value("${ANTHROPIC_API_BASE_URL:https://api.anthropic.com/v1}") String anthropicBaseUrl,
		@Value("${XAI_API_KEY:}") String xAiApiKey,
		@Value("${XAI_MODEL:grok-4.20-0309-reasoning}") String xAiModel,
		@Value("${XAI_API_BASE_URL:https://api.x.ai/v1}") String xAiBaseUrl
	) {
		this.restClient = RestClient.builder().build();
		this.openAiApiKey = openAiApiKey;
		this.openAiModel = openAiModel;
		this.openAiBaseUrl = trimTrailingSlash(openAiBaseUrl);
		this.anthropicApiKey = anthropicApiKey;
		this.anthropicModel = anthropicModel;
		this.anthropicBaseUrl = trimTrailingSlash(anthropicBaseUrl);
		this.xAiApiKey = xAiApiKey;
		this.xAiModel = xAiModel;
		this.xAiBaseUrl = trimTrailingSlash(xAiBaseUrl);
	}

	@Override
	public AiReplyResult generateReply(AiProvider provider, String title, String body) {
		String prompt = """
			게시글 제목:
			%s

			게시글 본문:
			%s
			""".formatted(title, body);

		return switch (provider) {
			case GPT -> new AiReplyResult(requestOpenAiCompatible("GPT", openAiBaseUrl, openAiApiKey, openAiModel, prompt), openAiModel);
			case CLAUDE -> new AiReplyResult(requestAnthropic(prompt), anthropicModel);
			case GROK -> new AiReplyResult(requestOpenAiCompatible("Grok", xAiBaseUrl, xAiApiKey, xAiModel, prompt), xAiModel);
		};
	}

	private String requestOpenAiCompatible(String label, String baseUrl, String apiKey, String model, String prompt) {
		requireKey(label, apiKey);

		try {
			JsonNode response = restClient.post()
				.uri(baseUrl + "/chat/completions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(Map.of(
					"model", model,
					"messages", List.of(
						Map.of("role", "system", "content", SYSTEM_PROMPT),
						Map.of("role", "user", "content", prompt)
					)
				))
				.retrieve()
				.body(JsonNode.class);
			return extractOpenAiContent(response, label);
		} catch (RestClientResponseException exception) {
			throw new AiReplyGenerationException(label + " reply generation failed: " + exception.getStatusCode().value());
		} catch (Exception exception) {
			throw new AiReplyGenerationException(label + " reply generation failed");
		}
	}

	private String requestAnthropic(String prompt) {
		requireKey("Claude", anthropicApiKey);

		try {
			JsonNode response = restClient.post()
				.uri(anthropicBaseUrl + "/messages")
				.header("x-api-key", anthropicApiKey)
				.header("anthropic-version", ANTHROPIC_VERSION)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(Map.of(
					"model", anthropicModel,
					"max_tokens", 600,
					"system", SYSTEM_PROMPT,
					"messages", List.of(Map.of("role", "user", "content", prompt))
				))
				.retrieve()
				.body(JsonNode.class);
			return extractAnthropicContent(response);
		} catch (RestClientResponseException exception) {
			throw new AiReplyGenerationException("Claude reply generation failed: " + exception.getStatusCode().value());
		} catch (Exception exception) {
			throw new AiReplyGenerationException("Claude reply generation failed");
		}
	}

	private String extractOpenAiContent(JsonNode response, String provider) {
		String content = response.path("choices").path(0).path("message").path("content").asText("").trim();
		if (!StringUtils.hasText(content)) {
			throw new AiReplyGenerationException(provider + " returned an empty reply");
		}
		return content;
	}

	private String extractAnthropicContent(JsonNode response) {
		JsonNode contentNodes = response.path("content");
		StringBuilder builder = new StringBuilder();
		for (JsonNode item : contentNodes) {
			if ("text".equals(item.path("type").asText())) {
				if (!builder.isEmpty()) {
					builder.append('\n');
				}
				builder.append(item.path("text").asText(""));
			}
		}

		String content = builder.toString().trim();
		if (!StringUtils.hasText(content)) {
			throw new AiReplyGenerationException("Claude returned an empty reply");
		}
		return content;
	}

	private void requireKey(String provider, String key) {
		if (!StringUtils.hasText(key)) {
			throw new AiProviderNotConfiguredException(provider);
		}
	}

	private String trimTrailingSlash(String value) {
		return value.replaceAll("/+$", "");
	}
}
