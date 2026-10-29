package com.tradedesk.desk.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link Llm} on OpenAI through Spring AI. The model name comes from {@code OPENAI_MODEL}, so switching models needs
 * no code change. Logs never include prompts or answers: they can hold the owner's business details.
 */
@Component
class OpenAiLlm implements Llm {

	private static final Logger log = LoggerFactory.getLogger(OpenAiLlm.class);

	/** The default in application.yml when OPENAI_API_KEY is unset. */
	private static final String NOT_CONFIGURED = "not-configured";

	private final ChatClient chat;

	private final boolean configured;

	OpenAiLlm(ChatClient.Builder chat, @Value("${spring.ai.openai.api-key:}") String apiKey) {
		this.chat = chat.build();
		this.configured = apiKey != null && !apiKey.isBlank() && !NOT_CONFIGURED.equals(apiKey);
	}

	@Override
	public boolean configured() {
		return configured;
	}

	@Override
	public String json(String system, String user, String schemaName, String jsonSchema) {
		requireConfigured();
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
			.responseFormat(OpenAiChatModel.ResponseFormat.builder()
				.type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
				.jsonSchema(jsonSchema)
				.strict(true)
				.build());
		return call(schemaName, () -> chat.prompt().options(options).system(system).user(user).call().content());
	}

	@Override
	public String text(String system, String user) {
		requireConfigured();
		return call("text", () -> chat.prompt().system(system).user(user).call().content());
	}

	private void requireConfigured() {
		if (!configured) {
			throw new LlmNotConfiguredException();
		}
	}

	private static String call(String what, java.util.function.Supplier<String> request) {
		String answer;
		try {
			answer = request.get();
		}
		catch (RuntimeException ex) {
			// The exception text can quote the request; log only its type.
			log.warn("OpenAI call for {} failed: {}", what, ex.getClass().getSimpleName());
			throw new LlmException("The AI model didn't answer. Try again in a moment.", ex);
		}
		if (answer == null || answer.isBlank()) {
			throw new LlmException("The AI model gave an empty answer. Try again.");
		}
		return answer;
	}

}
