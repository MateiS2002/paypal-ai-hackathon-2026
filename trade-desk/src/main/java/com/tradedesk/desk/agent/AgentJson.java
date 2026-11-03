package com.tradedesk.desk.agent;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

	private static final int MAX_FIELD_NAME = 40;

	private static final int MAX_FIELD_NAMES = 3;

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

	/**
	 * The names of the fields in {@code body} that {@code type} (a record) doesn't have, for telling the owner why a
	 * message was refused. Only for display: the strict parse in {@link #body} is what refuses the message. Each name
	 * is cut and cleaned, because it comes from the sender.
	 */
	public List<String> unknownFields(JsonNode body, Class<?> type) {
		if (body == null || !body.isObject() || !type.isRecord()) {
			return List.of();
		}
		Set<String> known = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
			.collect(Collectors.toSet());
		return body.propertyNames().stream()
			.filter(name -> !known.contains(name))
			.map(name -> name.replaceAll("[^A-Za-z0-9_.-]", "?"))
			.map(name -> name.length() > MAX_FIELD_NAME ? name.substring(0, MAX_FIELD_NAME) + "…" : name)
			.limit(MAX_FIELD_NAMES)
			.toList();
	}

	public String write(Object value) {
		return strict.writeValueAsString(value);
	}

	public JsonNode tree(Object value) {
		return strict.valueToTree(value);
	}

}
