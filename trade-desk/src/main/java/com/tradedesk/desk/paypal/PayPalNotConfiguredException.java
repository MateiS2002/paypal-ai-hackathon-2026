package com.tradedesk.desk.paypal;

/** This desk has no PayPal sandbox credentials, so it cannot send invoices (503). */
public class PayPalNotConfiguredException extends RuntimeException {

	public PayPalNotConfiguredException() {
		super("This desk has no PayPal sandbox credentials. Set PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET in .env "
				+ "and restart it.");
	}

}
