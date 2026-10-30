package com.tradedesk.desk.llm;

/** The LLM call failed or answered something unusable. The message is safe to show the user (502). */
public class LlmException extends RuntimeException {

	public LlmException(String message) {
		super(message);
	}

	public LlmException(String message, Throwable cause) {
		super(message, cause);
	}

}
