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
public class ExternalAiReplyGenerator implements AiReplyGenerator {
	private static final String ANTHROPIC_VERSION = "2023-06-01";
	private static final String SYSTEM_PROMPT =
		"당신은 익명 게시판의 답변 작성 도우미입니다. 게시글을 읽고 한국어로 간결하고 도움이 되는 답변을 작성하세요. "
			+ "불필요한 인사말이나 마크다운 제목은 쓰지 말고, 본문만 평문으로 답하세요.";

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
	public String generateReply(AiProvider provider, String title, String body) {
		String prompt = """
			게시글 제목:
			%s

			게시글 본문:
			%s
			""".formatted(title, body);

		return switch (provider) {
			case GPT -> requestOpenAi(prompt);
			case CLAUDE -> requestAnthropic(prompt);
			case GROK -> requestXAi(prompt);
		};
	}

	private String requestOpenAi(String prompt) {
		requireKey("GPT", openAiApiKey);

		try {
			JsonNode response = restClient.post()
				.uri(openAiBaseUrl + "/chat/completions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(Map.of(
					"model", openAiModel,
					"messages", List.of(
						Map.of("role", "system", "content", SYSTEM_PROMPT),
						Map.of("role", "user", "content", prompt)
					)
				))
				.retrieve()
				.body(JsonNode.class);
			return extractOpenAiContent(response, "GPT");
		} catch (RestClientResponseException exception) {
			throw new AiReplyGenerationException("GPT reply generation failed: " + exception.getStatusCode().value());
		} catch (Exception exception) {
			throw new AiReplyGenerationException("GPT reply generation failed");
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

	private String requestXAi(String prompt) {
		requireKey("Grok", xAiApiKey);

		try {
			JsonNode response = restClient.post()
				.uri(xAiBaseUrl + "/chat/completions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + xAiApiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(Map.of(
					"model", xAiModel,
					"messages", List.of(
						Map.of("role", "system", "content", SYSTEM_PROMPT),
						Map.of("role", "user", "content", prompt)
					)
				))
				.retrieve()
				.body(JsonNode.class);
			return extractOpenAiContent(response, "Grok");
		} catch (RestClientResponseException exception) {
			throw new AiReplyGenerationException("Grok reply generation failed: " + exception.getStatusCode().value());
		} catch (Exception exception) {
			throw new AiReplyGenerationException("Grok reply generation failed");
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
