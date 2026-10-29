package com.tradedesk.desk.paypal;

/**
 * A PayPal call failed. The message is written for the user (shown in the UI as a 502) and never contains a token,
 * a credential or a stack trace.
 */
public class PayPalException extends RuntimeException {

	private final int status;

	public PayPalException(String message, int status) {
		super(message);
		this.status = status;
	}

	public PayPalException(String message, Throwable cause) {
		super(message, cause);
		this.status = 0;
	}

	/** PayPal's HTTP status, or 0 if PayPal could not be reached. */
	public int status() {
		return status;
	}

}
