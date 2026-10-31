package com.tradedesk.desk.agent;

/**
 * This desk can't answer a well-formed request right now, for example because PayPal failed (503). No reply is stored,
 * so the sender's retry with the same {@code messageId} is handled afresh.
 */
public class AgentUnavailableException extends RuntimeException {

	public AgentUnavailableException(String message) {
		super(message);
	}

}
