package com.tradedesk.desk.buying;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.need.QuoteRow;
import com.tradedesk.desk.policy.PolicyDecision;

/**
 * The policy engine's decision on a need's pick, the owner's answer when one was needed, and how the accept went.
 * What {@code approval.needed} carries; shape in contracts/desk-api.md (Approval).
 *
 * @param quoteId the café's quote row id
 * @param orderDetail why the order was refused or failed, safe to show; otherwise null
 */
public record Approval(UUID id, UUID needId, UUID quoteId, QuoteRow.Supplier supplier, Money total,
		PolicyDecision.Outcome decision, List<String> reasons, Status status, Order order, String orderDetail,
		Instant createdAt, Instant decidedAt) {

	public enum Status {

		/** The policy engine said {@code AUTO_ACCEPT}. */
		AUTO_APPROVED,

		/** Waiting for the owner's tap. */
		PENDING,

		APPROVED, REJECTED,

		/** The policy engine said {@code BLOCKED}, at the pick or when the owner tapped Approve. */
		BLOCKED

	}

	/** The accept sent to the supplier. */
	public enum Order {

		NOT_SENT, SENDING,

		/** The supplier answered with an invoice the café accepted; there is a payable. */
		INVOICED,

		/** The supplier answered {@code reject}. */
		REFUSED,

		/** No answer, or an invoice the café refused. */
		FAILED

	}

}
