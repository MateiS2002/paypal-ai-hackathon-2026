package com.tradedesk.desk.contract;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;

/**
 * Every desk-to-desk message. Slices define each {@link Type}'s {@code body}; until a slice parses it into a typed
 * record and validates it, the body is untrusted input. Schema: contracts/envelope.schema.json.
 */
public record AgentEnvelope(UUID messageId, Type type, String from, String to, UUID conversationId, Instant sentAt,
		JsonNode body) {

	public enum Type {

		RFQ("rfq"), QUOTE("quote"), COUNTER("counter"), ACCEPT("accept"), REJECT("reject"), INVOICE("invoice"),
		INVOICE_STATUS("invoice.status");

		private final String wireName;

		Type(String wireName) {
			this.wireName = wireName;
		}

		@JsonValue
		public String wireName() {
			return wireName;
		}

	}

}
