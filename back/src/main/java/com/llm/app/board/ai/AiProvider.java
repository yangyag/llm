package com.llm.app.board.ai;

import com.llm.app.board.exception.InvalidAiProviderException;

/**
 * @deprecated 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 데이터 조회/보호용으로만 유지되며 신규 호출 금지.
 */
@Deprecated
public enum AiProvider {
	GPT("GPT"),
	CLAUDE("Claude"),
	GROK("Grok");

	private final String label;

	AiProvider(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public static AiProvider from(String value) {
		for (AiProvider provider : values()) {
			if (provider.label.equalsIgnoreCase(value) || provider.name().equalsIgnoreCase(value)) {
				return provider;
			}
		}
		throw new InvalidAiProviderException(value);
	}
}
