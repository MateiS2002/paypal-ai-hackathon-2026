package com.tradedesk.desk.need;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One posted need: what {@code GET /api/needs} lists and {@code need.updated} carries (contracts/desk-api.md).
 *
 * @param refused the suppliers whose answer to the RFQ was refused as malformed (S8), with the reason
 */
public record NeedRow(UUID id, PurchaseRequest request, Status status, int suppliersAsked, int quotesReceived,
		List<Refusal> refused, Instant createdAt) {

	public NeedRow {
		refused = List.copyOf(refused);
	}

	public enum Status {

		/** RFQs are out. */
		COLLECTING,

		/** Every supplier has answered, refused or timed out. */
		COMPLETE

	}

	/** @param reason at most {@link QuoteValidator#MAX_PROBLEM} characters, safe to show */
	public record Refusal(String domain, String reason) {
	}

}
