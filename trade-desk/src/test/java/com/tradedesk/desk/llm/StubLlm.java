package com.tradedesk.desk.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** An {@link Llm} for tests: answers with whatever the test sets, and records what it was asked. */
public class StubLlm implements Llm {

	private volatile boolean configured = true;

	private volatile BiFunction<String, String, String> answer = (system, user) -> "{}";

	public final List<String> userMessages = new ArrayList<>();

	public StubLlm answering(String fixed) {
		this.answer = (system, user) -> fixed;
		return this;
	}

	public StubLlm answering(BiFunction<String, String, String> answer) {
		this.answer = answer;
		return this;
	}

	public StubLlm failing() {
		this.answer = (system, user) -> {
			throw new LlmException("The AI model didn't answer. Try again in a moment.");
		};
		return this;
	}

	public StubLlm notConfigured() {
		this.configured = false;
		return this;
	}

	@Override
	public boolean configured() {
		return configured;
	}

	@Override
	public synchronized String json(String system, String user, String schemaName, String jsonSchema) {
		userMessages.add(user);
		return answer.apply(system, user);
	}

	@Override
	public synchronized String text(String system, String user) {
		userMessages.add(user);
		return answer.apply(system, user);
	}

}
