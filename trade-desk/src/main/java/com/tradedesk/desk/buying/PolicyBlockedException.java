package com.tradedesk.desk.buying;

import java.util.List;

/**
 * The policy engine says an order is {@code BLOCKED}, so it can't be approved or sent (422, S8). Carries the engine's
 * own sentences, which the problem body shows as {@code detail} and lists as {@code reasons}.
 */
public class PolicyBlockedException extends RuntimeException {

	private final List<String> reasons;

	public PolicyBlockedException(String prefix, List<String> reasons) {
		super(prefix + " " + String.join(" ", reasons));
		this.reasons = List.copyOf(reasons);
	}

	public List<String> reasons() {
		return reasons;
	}

}
