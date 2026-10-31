package com.tradedesk.desk.policy;

import java.time.Instant;
import java.util.Optional;

/**
 * One check {@link PolicyEngine} runs on every order. The standard rules are built into the engine and can't be
 * removed; a later slice adds its own by declaring a {@code PolicyRule} bean (S8: a quote that moved after a
 * prompt-injected message). Plain Java: a rule never asks an LLM.
 */
@FunctionalInterface
public interface PolicyRule {

	/** A finding if the rule applies to this order, or empty. */
	Optional<PolicyDecision.Finding> check(ProposedOrder order, Policy policy, Instant now);

}
