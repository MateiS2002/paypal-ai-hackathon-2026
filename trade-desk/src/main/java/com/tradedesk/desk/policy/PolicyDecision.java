package com.tradedesk.desk.policy;

import java.util.List;

/**
 * The policy engine's answer for one order, with the sentences shown to the owner.
 *
 * @param reasons at least one, in rule order
 */
public record PolicyDecision(Outcome outcome, List<String> reasons) {

	public PolicyDecision {
		reasons = List.copyOf(reasons);
	}

	/** Least to most strict; the strictest finding wins. */
	public enum Outcome {

		/** The café sends the accept at once. */
		AUTO_ACCEPT,

		/** Nothing is sent until the owner taps Approve. */
		NEEDS_APPROVAL,

		/** Nothing is ever sent for this order. */
		BLOCKED

	}

	/** One rule's verdict. */
	public record Finding(Outcome outcome, String reason) {
	}

}
