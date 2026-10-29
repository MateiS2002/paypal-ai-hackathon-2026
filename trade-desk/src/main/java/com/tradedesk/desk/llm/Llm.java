package com.tradedesk.desk.llm;

/**
 * The desk's only way to reach a language model. What comes back is untrusted text: callers parse it into typed
 * records and check every field in Java before using it, and nothing an LLM returns ever moves money or skips a rule.
 * The model gets no tools and never sees a credential.
 */
public interface Llm {

	/** False when no API key is set; callers then answer 503 or fall back to plain Java. */
	boolean configured();

	/**
	 * Asks for a JSON document that matches {@code jsonSchema} (OpenAI strict structured output).
	 * @return the raw JSON text; still untrusted until the caller has parsed and validated it
	 * @throws LlmException if the call fails
	 */
	String json(String system, String user, String schemaName, String jsonSchema);

	/**
	 * Asks for plain text, for display only.
	 * @throws LlmException if the call fails
	 */
	String text(String system, String user);

}
