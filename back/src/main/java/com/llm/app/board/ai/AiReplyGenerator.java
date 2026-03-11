package com.llm.app.board.ai;

public interface AiReplyGenerator {
	String generateReply(AiProvider provider, String title, String body);
}
