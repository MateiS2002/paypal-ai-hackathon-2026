package com.tradedesk.desk.invoice;

/** The invoice request breaks a rule, so nothing was sent to PayPal. The message is safe to show the user (400). */
public class InvalidInvoiceException extends RuntimeException {

	public InvalidInvoiceException(String message) {
		super(message);
	}

}
