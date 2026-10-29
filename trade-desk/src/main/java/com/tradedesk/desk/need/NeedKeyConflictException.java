package com.tradedesk.desk.need;

/** The same {@code Idempotency-Key} came with a different need (409). */
public class NeedKeyConflictException extends RuntimeException {

	public NeedKeyConflictException() {
		super("This Idempotency-Key was already used for a different need. Use a new key for a new need.");
	}

}
