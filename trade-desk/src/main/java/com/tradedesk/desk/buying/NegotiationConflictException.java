package com.tradedesk.desk.buying;

/** A negotiation step that can't happen now (409): still collecting, already decided, or blocked by the policy. */
public class NegotiationConflictException extends RuntimeException {

	public NegotiationConflictException(String message) {
		super(message);
	}

}
