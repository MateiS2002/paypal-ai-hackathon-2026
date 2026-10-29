package com.tradedesk.desk.need;

/** The purchase request breaks a rule, so nothing was stored or sent. The message is safe to show the user (400). */
public class InvalidNeedException extends RuntimeException {

	public InvalidNeedException(String message) {
		super(message);
	}

}
