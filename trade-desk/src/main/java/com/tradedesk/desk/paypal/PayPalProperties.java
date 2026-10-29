package com.tradedesk.desk.paypal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This desk's PayPal sandbox REST app, from {@code PAYPAL_CLIENT_ID} and {@code PAYPAL_CLIENT_SECRET} in .env. Only
 * desks that send invoices need it; without it the invoice routes answer 503. There is deliberately no base URL
 * setting: see {@link PayPalClient#SANDBOX_BASE_URL}.
 *
 * @param clientId sandbox REST app client ID
 * @param clientSecret sandbox REST app secret; never logged, never returned by any endpoint
 * @param sandboxBuyerEmail the demo's sandbox buyer, seeded as the customer Bean There
 */
@ConfigurationProperties("paypal")
public record PayPalProperties(String clientId, String clientSecret, String sandboxBuyerEmail) {

	public boolean configured() {
		return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
	}

	@Override
	public String toString() {
		// Keep the credentials out of logs and actuator output.
		return "PayPalProperties[configured=%s]".formatted(configured());
	}

}
