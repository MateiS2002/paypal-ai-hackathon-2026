package com.tradedesk.desk.agent;

import java.time.Clock;
import java.util.UUID;

import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;

import org.springframework.stereotype.Component;

/**
 * Builds this desk's envelopes: replies (sender and receiver swapped, same conversation, new {@code messageId}) and new
 * requests to a peer.
 */
@Component
public class AgentReplies {

	private final AgentJson json;

	private final DeskProperties desk;

	private final Clock clock;

	public AgentReplies(AgentJson json, DeskProperties desk, Clock clock) {
		this.json = json;
		this.desk = desk;
		this.clock = clock;
	}

	public AgentEnvelope reply(AgentEnvelope to, AgentEnvelope.Type type, Object body) {
		return new AgentEnvelope(UUID.randomUUID(), type, desk.domain(), to.from(), to.conversationId(),
				clock.instant(), json.tree(body));
	}

	/** A {@code reject} of {@code to}, with the reason cut to the contract's 200 characters. */
	public AgentEnvelope reject(AgentEnvelope to, String reason) {
		String text = reason.length() > RejectBody.MAX_REASON ? reason.substring(0, RejectBody.MAX_REASON) : reason;
		return reply(to, AgentEnvelope.Type.REJECT, new RejectBody(to.messageId(), text));
	}

	public AgentEnvelope request(UUID messageId, AgentEnvelope.Type type, String to, UUID conversationId, Object body) {
		return new AgentEnvelope(messageId, type, desk.domain(), to, conversationId, clock.instant(), json.tree(body));
	}

}
