package com.tradedesk.desk.need;

import java.math.RoundingMode;
import java.time.Clock;

import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.policy.PolicyDecision;
import com.tradedesk.desk.policy.PolicyEngine;
import com.tradedesk.desk.policy.PolicyRepository;
import com.tradedesk.desk.policy.ProposedOrder;

import org.springframework.stereotype.Component;

/**
 * Asks the policy engine about a quote the moment the café has it (S8), so the grid can show a {@code BLOCKED} quote
 * with the engine's own reasons and the negotiation can leave it out. Plain Java: the order is built from the checked
 * quote and the café's own request, and nothing a supplier wrote in text reaches the engine.
 */
@Component
public class QuoteScreen {

	private final PolicyEngine engine;

	private final PolicyRepository policies;

	private final Clock clock;

	public QuoteScreen(PolicyEngine engine, PolicyRepository policies, Clock clock) {
		this.engine = engine;
		this.policies = policies;
		this.clock = clock;
	}

	/** A quote that passed {@link QuoteValidator} and isn't stored yet. */
	public PolicyDecision screen(PurchaseRequest request, String supplierDomain, QuoteBody quote) {
		Money total = new Money(quote.quantity().multiply(quote.unitPrice().amount()).setScale(2, RoundingMode.HALF_UP),
				quote.unitPrice().currency());
		ProposedOrder order = new ProposedOrder(supplierDomain, quote.unitPrice(), quote.quantity(), quote.unit(),
				request.quantity(), total, quote.validUntil(), request.maxUnitPrice());
		return engine.evaluate(order, policies.get(), clock.instant());
	}

	/** A stored quote, with the current policy and time. */
	public PolicyDecision screen(PurchaseRequest request, QuoteRow quote) {
		return engine.evaluate(quote.proposedOrder(request), policies.get(), clock.instant());
	}

}
