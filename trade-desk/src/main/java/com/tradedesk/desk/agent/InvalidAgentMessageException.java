package com.tradedesk.desk.agent;

/** An inbound agent message this desk won't read (400). The message names what was wrong, never echoes the body. */
public class InvalidAgentMessageException extends RuntimeException {

	public InvalidAgentMessageException(String message) {
		super(message);
	}

}
