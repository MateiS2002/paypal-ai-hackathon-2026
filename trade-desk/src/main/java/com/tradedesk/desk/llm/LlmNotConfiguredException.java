package com.tradedesk.desk.llm;

/** This desk has no OpenAI key, so it can't parse a need (503). */
public class LlmNotConfiguredException extends RuntimeException {

	public LlmNotConfiguredException() {
		super("This desk has no OpenAI key. Set OPENAI_API_KEY in .env and restart it.");
	}

}
