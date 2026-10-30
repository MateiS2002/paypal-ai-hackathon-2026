package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.tradedesk.desk.contract.Money;

/**
 * The café owner's limits, which {@link PolicyEngine} checks before any accept. All three are in {@link #currency()};
 * shape and rules in contracts/desk-api.md (Policy).
 *
 * @param maxUnitPrice no quote above this unit price is ever accepted
 * @param approvalAbove an order whose total is above this waits for the owner's tap
 * @param maxOrderTotal no order above this total is ever accepted
 * @param updatedAt when the owner last changed it; null for a policy that isn't stored yet
 */
public record Policy(Money maxUnitPrice, Money approvalAbove, Money maxOrderTotal, Instant updatedAt) {

	public static final String CURRENCY = "USD";

	public Policy {
		Objects.requireNonNull(maxUnitPrice, "maxUnitPrice");
		Objects.requireNonNull(approvalAbove, "approvalAbove");
		Objects.requireNonNull(maxOrderTotal, "maxOrderTotal");
	}

	public String currency() {
		return maxUnitPrice.currency();
	}

	static Policy of(String maxUnitPrice, String approvalAbove, String maxOrderTotal) {
		return new Policy(usd(maxUnitPrice), usd(approvalAbove), usd(maxOrderTotal), null);
	}

	private static Money usd(String amount) {
		return new Money(new BigDecimal(amount), CURRENCY);
	}

}
