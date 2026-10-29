package com.tradedesk.desk.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tradedesk.desk.contract.AgentEnvelope;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * This desk's own copy of every agent message it sent or received ({@code conversation_message}), including the ones
 * it refused. The registry never gets these, only fingerprints (S5).
 */
@Repository
public class ConversationLog {

	/** A peer's reply can be anything; keep at most this much of it. */
	static final int MAX_RAW = 64 * 1024;

	private final JdbcClient jdbc;

	public ConversationLog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void sent(AgentEnvelope envelope, String raw, UUID inReplyTo) {
		insert("SENT", envelope, envelope.conversationId(), raw, inReplyTo, true, null);
	}

	/** A message that was read and accepted as valid. */
	public void received(AgentEnvelope envelope, String raw) {
		insert("RECEIVED", envelope, envelope.conversationId(), raw, null, true, null);
	}

	/**
	 * A message that was refused. {@code envelope} is null if the text wasn't even an envelope; {@code conversationId}
	 * is ours when we know which conversation it belonged to.
	 */
	public void refused(AgentEnvelope envelope, UUID conversationId, String raw, String problem) {
		insert("RECEIVED", envelope, conversationId, raw, null, false, problem);
	}

	/** The reply this desk already sent to the message {@code messageId}, if any. */
	public Optional<String> replyTo(UUID messageId) {
		return jdbc.sql("""
				select raw from conversation_message
				 where direction = 'SENT' and in_reply_to = :messageId
				""").param("messageId", messageId).query(String.class).optional();
	}

	public List<Entry> conversation(UUID conversationId) {
		return jdbc.sql("""
				select direction, message_id, type, from_domain, to_domain, valid, problem
				  from conversation_message where conversation_id = :id order by created_at, id
				""")
			.param("id", conversationId)
			.query((rs, n) -> new Entry(rs.getString("direction"), rs.getObject("message_id", UUID.class),
					rs.getString("type"), rs.getString("from_domain"), rs.getString("to_domain"),
					rs.getBoolean("valid"), rs.getString("problem")))
			.list();
	}

	private void insert(String direction, AgentEnvelope envelope, UUID conversationId, String raw, UUID inReplyTo,
			boolean valid, String problem) {
		String kept = raw == null ? "" : raw.length() > MAX_RAW ? raw.substring(0, MAX_RAW) : raw;
		jdbc.sql("""
				insert into conversation_message (id, direction, conversation_id, message_id, type, from_domain,
				                                  to_domain, in_reply_to, raw, valid, problem)
				values (:id, :direction, :conversationId, :messageId, :type, :from, :to, :inReplyTo, :raw, :valid,
				        :problem)
				""")
			.param("id", UUID.randomUUID())
			.param("direction", direction)
			.param("conversationId", conversationId)
			.param("messageId", envelope == null ? null : envelope.messageId())
			.param("type", envelope == null || envelope.type() == null ? null : envelope.type().wireName())
			.param("from", envelope == null ? null : envelope.from())
			.param("to", envelope == null ? null : envelope.to())
			.param("inReplyTo", inReplyTo)
			.param("raw", kept)
			.param("valid", valid)
			.param("problem", problem)
			.update();
	}

	public record Entry(String direction, UUID messageId, String type, String from, String to, boolean valid,
			String problem) {
	}

}
