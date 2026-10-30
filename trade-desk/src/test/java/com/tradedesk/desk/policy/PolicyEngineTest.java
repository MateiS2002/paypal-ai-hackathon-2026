package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.policy.PolicyDecision.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 acceptance criterion 2, in plain Java with no LLM anywhere: $499 auto-accepts, $501 waits for approval, and a
 * quote above {@code maxUnitPrice} is BLOCKED with the reason shown.
 */
class PolicyEngineTest {

	static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

	static final Instant LATER = Instant.parse("2026-11-04T10:00:00Z");

	/** The defaults: max $15/kg, ask above $500, never above $2,000. */
	static final Policy POLICY = Policy.of("15.00", "500.00", "2000.00");

	private final PolicyEngine engine = new PolicyEngine();

	/** A full offer of {@code quantity} kg at {@code price}, total computed the way the café computes it. */
	static ProposedOrder order(String quantity, String price) {
		return order(quantity, quantity, price, Unit.KG, null);
	}

	static ProposedOrder order(String quantity, String requested, String price, Unit unit, Money budget) {
		BigDecimal q = new BigDecimal(quantity);
		BigDecimal p = new BigDecimal(price);
		return new ProposedOrder("roaster.test", usd(price), q, unit, new BigDecimal(requested),
				usd(q.multiply(p).setScale(2, RoundingMode.HALF_UP).toPlainString()), LATER, budget);
	}

	static Money usd(String amount) {
		return new Money(new BigDecimal(amount), "USD");
	}

	@Test
	void anOrderOf499AutoAccepts() {
		PolicyDecision decision = engine.evaluate(order("49.9", "10.00"), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.AUTO_ACCEPT);
		assertThat(decision.reasons()).containsExactly("The total 499.00 USD is within your approval limit of 500.00 USD.");
	}

	@Test
	void anOrderOf501WaitsForApproval() {
		PolicyDecision decision = engine.evaluate(order("50.1", "10.00"), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.NEEDS_APPROVAL);
		assertThat(decision.reasons()).containsExactly("The total 501.00 USD is above your approval limit of 500.00 USD.");
	}

	@Test
	void aboveIsStrict() {
		assertThat(engine.evaluate(order("50", "10.00"), POLICY, NOW).outcome()).isEqualTo(Outcome.AUTO_ACCEPT);
		assertThat(engine.evaluate(order("1", "500.01"), Policy.of("600.00", "500.00", "2000.00"), NOW).outcome())
			.isEqualTo(Outcome.NEEDS_APPROVAL);
	}

	@Test
	void aUnitPriceAboveTheMaximumIsBlockedWithTheReason() {
		PolicyDecision decision = engine.evaluate(order("10", "15.01"), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(decision.reasons())
			.containsExactly("The unit price 15.01 USD per kg is above your maximum of 15.00 USD per kg.");
	}

	@Test
	void aPricePerPoundIsComparedPerKilogram() {
		// 6.80 USD/lb is 14.99 USD/kg: allowed. 6.81 USD/lb is 15.01 USD/kg: blocked.
		assertThat(engine.evaluate(order("10", "10", "6.80", Unit.LB, null), POLICY, NOW).outcome())
			.isEqualTo(Outcome.AUTO_ACCEPT);
		PolicyDecision decision = engine.evaluate(order("10", "10", "6.81", Unit.LB, null), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(decision.reasons()).containsExactly(
				"The unit price 6.81 USD per lb (15.01 USD per kg) is above your maximum of 15.00 USD per kg.");
		// A counter target capped at the per-lb limit never trips the rule.
		Money capped = PolicyEngine.maxUnitPrice(POLICY, Unit.LB);
		assertThat(capped.amount()).isEqualByComparingTo("6.80");
		assertThat(engine.evaluate(order("10", "10", capped.amount().toPlainString(), Unit.LB, null), POLICY, NOW)
			.outcome()).isEqualTo(Outcome.AUTO_ACCEPT);
	}

	@Test
	void aPartialOfferAlwaysWaitsForTheOwner() {
		PolicyDecision decision = engine.evaluate(order("20", "50", "11.20", Unit.KG, null), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.NEEDS_APPROVAL);
		assertThat(decision.reasons()).containsExactly("The supplier offers 20 kg of the 50 kg you asked for.");
	}

	@Test
	void theStrictestOutcomeWinsAndEveryReasonIsKept() {
		// 50 kg at 15.40: above the unit maximum (blocked) and above the approval limit (needs approval).
		PolicyDecision decision = engine.evaluate(order("50", "15.40"), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(decision.reasons()).containsExactly(
				"The unit price 15.40 USD per kg is above your maximum of 15.00 USD per kg.",
				"The total 770.00 USD is above your approval limit of 500.00 USD.");
	}

	@Test
	void aTotalAboveTheMaximumOrderIsBlocked() {
		PolicyDecision decision = engine.evaluate(order("200", "10.01"), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(decision.reasons()).contains("The total 2002.00 USD is above your maximum order of 2000.00 USD.");
	}

	@Test
	void aPriceAboveTheNeedsOwnBudgetIsBlocked() {
		PolicyDecision decision = engine.evaluate(order("10", "10", "12.00", Unit.KG, usd("11.50")), POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(decision.reasons()).containsExactly("The unit price 12.00 USD is above this need's budget of 11.50 USD.");
	}

	@Test
	void anExpiredQuoteIsBlocked() {
		PolicyDecision decision = engine.evaluate(order("10", "10.00"), POLICY, LATER);

		assertThat(decision.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(decision.reasons()).containsExactly("The quote expired at 2026-11-04T10:00:00Z.");
	}

	@Test
	void anotherCurrencyIsBlockedWithoutComparingAmounts() {
		ProposedOrder euros = new ProposedOrder("roaster.test", new Money(new BigDecimal("1.00"), "EUR"),
				BigDecimal.ONE, Unit.KG, BigDecimal.ONE, new Money(new BigDecimal("1.00"), "EUR"), LATER, null);

		PolicyDecision decision = engine.evaluate(euros, POLICY, NOW);

		assertThat(decision.outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(decision.reasons()).containsExactly("The quote is in EUR but your limits are in USD.");
	}

	@Test
	void aTotalThatIsntQuantityTimesPriceIsBlocked() {
		ProposedOrder order = order("50", "10.00");
		ProposedOrder cheaper = new ProposedOrder(order.supplierDomain(), order.unitPrice(), order.quantity(),
				order.unit(), order.requestedQuantity(), usd("400.00"), order.validUntil(), null);

		assertThat(engine.evaluate(cheaper, POLICY, NOW).outcome()).isEqualTo(Outcome.BLOCKED);
	}

	@Test
	void anExtraRuleCanOnlyAddAFinding() {
		// The S8 hook: a later slice adds a rule; it can block, but it can't take a standard finding away.
		PolicyRule suspicious = (order, policy, now) -> Optional
			.of(new PolicyDecision.Finding(Outcome.BLOCKED, "This supplier's quote changed after a suspicious message."));
		PolicyRule lenient = (order, policy, now) -> Optional.empty();
		PolicyEngine withS8 = new PolicyEngine(List.of(suspicious, lenient));

		assertThat(withS8.evaluate(order("10", "10.00"), POLICY, NOW).outcome()).isEqualTo(Outcome.BLOCKED);
		assertThat(new PolicyEngine(List.of(lenient)).evaluate(order("10", "15.50"), POLICY, NOW).outcome())
			.isEqualTo(Outcome.BLOCKED);
	}

}
