package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
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
 * <li>a unit price above {@code maxUnitPrice} (per kg; a price per lb is converted first), or above the need's own
 * budget, is {@code BLOCKED};</li>
 * <li>a total above {@code maxOrderTotal} is {@code BLOCKED};</li>
 * <li>a total above {@code approvalAbove} {@code NEEDS_APPROVAL};</li>
 * <li>an offer of less than the need's quantity {@code NEEDS_APPROVAL};</li>
 * <li>then any extra {@link PolicyRule} beans (S8).</li>
 * </ol>
 * Every rule that applies adds a reason, and the strictest outcome wins. "Above" is strict.
 */
@Component
public class PolicyEngine {

	/** Exact, by definition of the international pound. */
	static final BigDecimal KG_PER_LB = new BigDecimal("0.45359237");

	private final List<PolicyRule> rules;

	/** The standard rules only. */
	public PolicyEngine() {
		this(List.of());
	}

	/** The standard rules, then {@code extra}; extra rules can only add findings, never remove one. */
	public PolicyEngine(List<PolicyRule> extra) {
		List<PolicyRule> all = new ArrayList<>(List.of(PolicyEngine::wrongTotal, PolicyEngine::expired,
				PolicyEngine::unitPriceAboveMax, PolicyEngine::unitPriceAboveBudget, PolicyEngine::totalAboveMax,
				PolicyEngine::totalAboveApproval, PolicyEngine::partial));
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

	/** {@code maxUnitPrice} is per kg; a price per lb is converted before it is compared. */
	private static Optional<Finding> unitPriceAboveMax(ProposedOrder order, Policy policy, Instant now) {
		BigDecimal perKg = order.unit() == Unit.LB
				? order.unitPrice().amount().divide(KG_PER_LB, 4, RoundingMode.HALF_UP) : order.unitPrice().amount();
		if (perKg.compareTo(policy.maxUnitPrice().amount()) <= 0) {
			return Optional.empty();
		}
		String price = order.unit() == Unit.LB ? text(order.unitPrice()) + " per lb ("
				+ perKg.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + policy.currency() + " per kg)"
				: text(order.unitPrice()) + " per kg";
		return blocked("The unit price " + price + " is above your maximum of " + text(policy.maxUnitPrice())
				+ " per kg.");
	}

	/**
	 * {@code maxUnitPrice} (per kg) in {@code unit}: the most a counter may target. Rounded down, so a target at this
	 * limit never fails the rule above.
	 */
	public static Money maxUnitPrice(Policy policy, Unit unit) {
		Money perKg = policy.maxUnitPrice();
		return unit == Unit.LB
				? new Money(perKg.amount().multiply(KG_PER_LB).setScale(2, RoundingMode.DOWN), perKg.currency())
				: perKg;
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

	/** The owner agrees to a smaller quantity before it is ordered, however cheap. */
	private static Optional<Finding> partial(ProposedOrder order, Policy policy, Instant now) {
		if (order.quantity().compareTo(order.requestedQuantity()) >= 0) {
			return Optional.empty();
		}
		String unit = " " + order.unit().wireName();
		return Optional.of(new Finding(Outcome.NEEDS_APPROVAL, "The supplier offers "
				+ order.quantity().stripTrailingZeros().toPlainString() + unit + " of the "
				+ order.requestedQuantity().stripTrailingZeros().toPlainString() + unit + " you asked for."));
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
