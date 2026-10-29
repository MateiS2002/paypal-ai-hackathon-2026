package com.tradedesk.desk.invoice;

/** The same {@code Idempotency-Key} came with a different invoice (409). */
public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException() {
		super("This Idempotency-Key was already used for a different invoice. Use a new key for a new invoice.");
	}

}
