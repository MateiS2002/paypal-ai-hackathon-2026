package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.policy.PolicyDecision.Finding;
import com.tradedesk.desk.policy.PolicyDecision.Outcome;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The café's money rules, in plain Java (S3). Every accept the café sends has been through {@link #evaluate} first:
 * when the buyer agent picks a winner, and again when the owner taps Approve. No LLM output reaches it; the order is
 * built by Java from the stored quote. The rules, in order (contracts/desk-api.md, Policy):
 * <ol>
 * <li>a quote in another currency than the policy's is {@code BLOCKED} (and nothing else is compared);</li>
 * <li>a total that isn't {@code quantity × unitPrice}, rounded half-up to the cent, is {@code BLOCKED};</li>
 * <li>a quote past its {@code validUntil} is {@code BLOCKED};</li>
 * <li>a unit price above {@code maxUnitPrice}, or above the need's own budget, is {@code BLOCKED};</li>
 * <li>a total above {@code maxOrderTotal} is {@code BLOCKED};</li>
 * <li>a total above {@code approvalAbove} {@code NEEDS_APPROVAL};</li>
 * <li>then any extra {@link PolicyRule} beans (S8).</li>
 * </ol>
 * Every rule that applies adds a reason, and the strictest outcome wins. "Above" is strict.
 */
@Component
public class PolicyEngine {

	private final List<PolicyRule> rules;

	/** The standard rules only. */
	public PolicyEngine() {
		this(List.of());
	}

	/** The standard rules, then {@code extra}; extra rules can only add findings, never remove one. */
	public PolicyEngine(List<PolicyRule> extra) {
		List<PolicyRule> all = new ArrayList<>(List.of(PolicyEngine::wrongTotal, PolicyEngine::expired,
				PolicyEngine::unitPriceAboveMax, PolicyEngine::unitPriceAboveBudget, PolicyEngine::totalAboveMax,
				PolicyEngine::totalAboveApproval));
		all.addAll(extra);
		this.rules = List.copyOf(all);
	}

	@Autowired
	PolicyEngine(ObjectProvider<PolicyRule> extra) {
		this(extra.orderedStream().toList());
	}

	public PolicyDecision evaluate(ProposedOrder order, Policy policy, Instant now) {
		String currency = policy.currency();
		if (!currency.equals(order.unitPrice().currency()) || !currency.equals(order.total().currency())) {
			return new PolicyDecision(Outcome.BLOCKED, List.of("The quote is in " + order.unitPrice().currency()
					+ " but your limits are in " + currency + "."));
		}
		List<Finding> findings = rules.stream()
			.map(rule -> rule.check(order, policy, now))
			.flatMap(Optional::stream)
			.toList();
		if (findings.isEmpty()) {
			return new PolicyDecision(Outcome.AUTO_ACCEPT, List.of("The total " + text(order.total())
					+ " is within your approval limit of " + text(policy.approvalAbove()) + "."));
		}
		Outcome strictest = findings.stream().map(Finding::outcome).max(Comparator.naturalOrder()).orElseThrow();
		return new PolicyDecision(strictest, findings.stream().map(Finding::reason).toList());
	}

	private static Optional<Finding> wrongTotal(ProposedOrder order, Policy policy, Instant now) {
		BigDecimal expected = order.quantity().multiply(order.unitPrice().amount()).setScale(2, RoundingMode.HALF_UP);
		return order.total().amount().compareTo(expected) == 0 ? Optional.empty()
				: blocked("The total " + text(order.total()) + " is not the quantity times the unit price ("
						+ expected.toPlainString() + ").");
	}

	private static Optional<Finding> expired(ProposedOrder order, Policy policy, Instant now) {
		return order.validUntil().isAfter(now) ? Optional.empty()
				: blocked("The quote expired at " + order.validUntil() + ".");
	}

	private static Optional<Finding> unitPriceAboveMax(ProposedOrder order, Policy policy, Instant now) {
		return above(order.unitPrice(), policy.maxUnitPrice()) ? blocked("The unit price " + text(order.unitPrice())
				+ " is above your maximum of " + text(policy.maxUnitPrice()) + ".") : Optional.empty();
	}

	private static Optional<Finding> unitPriceAboveBudget(ProposedOrder order, Policy policy, Instant now) {
		Money budget = order.needMaxUnitPrice();
		if (budget == null) {
			return Optional.empty();
		}
		if (!budget.currency().equals(order.unitPrice().currency())) {
			return blocked("This need's budget is in " + budget.currency() + ", not in the quote's currency.");
		}
		return above(order.unitPrice(), budget) ? blocked("The unit price " + text(order.unitPrice())
				+ " is above this need's budget of " + text(budget) + ".") : Optional.empty();
	}

	private static Optional<Finding> totalAboveMax(ProposedOrder order, Policy policy, Instant now) {
		return above(order.total(), policy.maxOrderTotal()) ? blocked("The total " + text(order.total())
				+ " is above your maximum order of " + text(policy.maxOrderTotal()) + ".") : Optional.empty();
	}

	private static Optional<Finding> totalAboveApproval(ProposedOrder order, Policy policy, Instant now) {
		return above(order.total(), policy.approvalAbove())
				? Optional.of(new Finding(Outcome.NEEDS_APPROVAL, "The total " + text(order.total())
						+ " is above your approval limit of " + text(policy.approvalAbove()) + "."))
				: Optional.empty();
	}

	private static boolean above(Money amount, Money limit) {
		return amount.amount().compareTo(limit.amount()) > 0;
	}

	private static Optional<Finding> blocked(String reason) {
		return Optional.of(new Finding(Outcome.BLOCKED, reason));
	}

	static String text(Money money) {
		return money.amount().setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + money.currency();
	}

}
