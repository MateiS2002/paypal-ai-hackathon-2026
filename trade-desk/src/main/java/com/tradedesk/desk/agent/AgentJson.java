package com.tradedesk.desk.agent;

import java.util.Optional;

import com.tradedesk.desk.contract.AgentEnvelope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Reads and writes agent messages. Reading is strict: unknown fields, a missing primitive or a wrong type fail, so a
 * message from another agent either fits our records exactly or is treated as malformed.
 */
@Component
public class AgentJson {

	private final JsonMapper strict = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.build();

	/** The envelope, or empty if the text isn't one. */
	public Optional<AgentEnvelope> envelope(String raw) {
		try {
			return Optional.ofNullable(strict.readValue(raw, AgentEnvelope.class));
		}
		catch (JacksonException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/** The body as {@code type}, or empty if it doesn't fit. Still needs its own rule checks. */
	public <T> Optional<T> body(JsonNode body, Class<T> type) {
		if (body == null || !body.isObject()) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(strict.treeToValue(body, type));
		}
		catch (JacksonException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public String write(Object value) {
		return strict.writeValueAsString(value);
	}

	public JsonNode tree(Object value) {
		return strict.valueToTree(value);
	}

}
