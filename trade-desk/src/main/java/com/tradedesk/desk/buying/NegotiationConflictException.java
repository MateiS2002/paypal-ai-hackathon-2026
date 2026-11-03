package com.tradedesk.desk.buying;

/** A negotiation step that can't happen now (409): still collecting or already decided. A policy block is 422. */
public class NegotiationConflictException extends RuntimeException {

	public NegotiationConflictException(String message) {
		super(message);
	}

}
