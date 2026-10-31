package com.tradedesk.desk.buying;

import java.util.UUID;

import com.tradedesk.desk.need.QuoteRow;

/**
 * Where a need's negotiation stands: what {@code GET /api/needs/{id}/recommendation} returns and
 * {@code recommendation.updated} carries. Shape in contracts/desk-api.md (Recommendation).
 *
 * @param pick the quote Java ranked first among those still open, or null before {@link Stage#READY}
 * @param reason one line for the owner, display text only, or null before {@link Stage#READY}
 * @param approval the policy engine's decision on the pick, or null before {@link Stage#READY}
 */
public record Recommendation(UUID needId, Stage stage, QuoteRow pick, String reason,
		RecommendationWriter.Reason.Source reasonSource, Approval approval) {

	public enum Stage {

		WAITING_FOR_QUOTES, READY_TO_COUNTER, COUNTERING, READY,

		/** No quote was left to pick: none arrived, or every one expired. */
		NO_OFFER

	}

}
