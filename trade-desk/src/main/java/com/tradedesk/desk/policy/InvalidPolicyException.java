package com.tradedesk.desk.policy;

/** A policy the owner tried to save that breaks a rule (400). The message is safe to show. */
public class InvalidPolicyException extends RuntimeException {

	public InvalidPolicyException(String message) {
		super(message);
	}

}
