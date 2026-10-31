package com.tradedesk.desk.invoice;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a {@link InvoiceSource#NEGOTIATION} invoice came from (S3): the conversation that ended in the accept, and the
 * buyer desk the poller tells about status changes.
 */
public record NegotiationLink(UUID conversationId, String buyerDomain) {

	public NegotiationLink {
		Objects.requireNonNull(conversationId, "conversationId");
		Objects.requireNonNull(buyerDomain, "buyerDomain");
	}

}
