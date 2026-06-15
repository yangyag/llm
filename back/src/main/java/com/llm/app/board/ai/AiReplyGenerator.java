package com.llm.app.board.ai;

public interface AiReplyGenerator {
	AiReplyResult generateReply(AiProvider provider, String title, String body);

	record AiReplyResult(String content, String model) {
	}
}
