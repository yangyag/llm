package com.llm.app.llm.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LlmDemoControllerTest {
	@Autowired
	private MockMvc mockMvc;

	@Test
	void generateShouldReturnPlaceholderResponse() throws Exception {
		mockMvc.perform(post("/api/v1/llm/demo")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "prompt": "Explain retrieval augmented generation",
					  "model": "demo-model"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prompt").value("Explain retrieval augmented generation"))
			.andExpect(jsonPath("$.model").value("demo-model"))
			.andExpect(jsonPath("$.response").value(org.hamcrest.Matchers.containsString("placeholder LLM response")))
			.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	void generateShouldRejectBlankPrompt() throws Exception {
		mockMvc.perform(post("/api/v1/llm/demo")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "prompt": "   "
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.path").value("/api/v1/llm/demo"));
	}
}
