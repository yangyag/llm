package com.llm.app.board.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ExternalAiReplyGeneratorDefaultsTest {

	@Test
	void constructorShouldPreserveConfiguredLatestDefaultModelNames() throws Exception {
		ExternalAiReplyGenerator generator = new ExternalAiReplyGenerator(
			"",
			"gpt-5.4",
			"https://api.openai.com/v1",
			"",
			"claude-sonnet-4-6",
			"https://api.anthropic.com/v1",
			"",
			"grok-4.20-0309-reasoning",
			"https://api.x.ai/v1"
		);

		assertThat(readField(generator, "openAiModel")).isEqualTo("gpt-5.4");
		assertThat(readField(generator, "anthropicModel")).isEqualTo("claude-sonnet-4-6");
		assertThat(readField(generator, "xAiModel")).isEqualTo("grok-4.20-0309-reasoning");
	}

	private Object readField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(target);
	}
}
