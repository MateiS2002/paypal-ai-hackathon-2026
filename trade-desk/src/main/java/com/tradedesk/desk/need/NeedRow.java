package com.tradedesk.desk.need;

import java.time.Instant;
import java.util.UUID;

/** One posted need: what {@code GET /api/needs} lists and {@code need.updated} carries (contracts/desk-api.md). */
public record NeedRow(UUID id, PurchaseRequest request, Status status, int suppliersAsked, int quotesReceived,
		Instant createdAt) {

	public enum Status {

		/** RFQs are out. */
		COLLECTING,

		/** Every supplier has answered, refused or timed out. */
		COMPLETE

	}

}
