package com.llm.app.board.ai;

import com.llm.app.board.exception.InvalidAiProviderException;

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
