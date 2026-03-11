package com.llm.app.llm.controller;

import com.llm.app.llm.dto.LlmDemoRequest;
import com.llm.app.llm.dto.LlmDemoResponse;
import com.llm.app.llm.service.LlmDemoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/llm")
public class LlmDemoController {
	private final LlmDemoService llmDemoService;

	public LlmDemoController(LlmDemoService llmDemoService) {
		this.llmDemoService = llmDemoService;
	}

	@PostMapping("/demo")
	public LlmDemoResponse generate(@Valid @RequestBody LlmDemoRequest request) {
		return llmDemoService.generate(request);
	}
}
