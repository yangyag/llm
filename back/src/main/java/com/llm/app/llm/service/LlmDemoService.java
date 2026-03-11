package com.llm.app.llm.service;

import com.llm.app.llm.dto.LlmDemoRequest;
import com.llm.app.llm.dto.LlmDemoResponse;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class LlmDemoService {

	public LlmDemoResponse generate(LlmDemoRequest request) {
		String model = (request.model() == null || request.model().isBlank())
			? "demo-model"
			: request.model().trim();

		String normalizedPrompt = request.prompt().trim();
		String response = """
			This is a placeholder LLM response.
			Received prompt: %s
			Next step: replace LlmDemoService with a real provider integration.
			""".formatted(normalizedPrompt);

		return new LlmDemoResponse(normalizedPrompt, response, model, Instant.now());
	}
}
