package com.tradedesk.desk.agent;

import java.util.UUID;

/**
 * The body of an {@code accept} (contracts/agent-messages.md). Sent only after the buyer's policy engine, and the owner
 * when it asks, agreed to the order.
 *
 * @param quoteId the {@code messageId} of the quote being accepted (the revised one, if there was a counter)
 * @param billTo who the PayPal invoice goes to
 */
public record AcceptBody(UUID quoteId, BillTo billTo) {

	public record BillTo(String name, String email) {
	}

}
