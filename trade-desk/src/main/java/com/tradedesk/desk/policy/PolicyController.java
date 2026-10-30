package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.time.Clock;

import com.tradedesk.desk.contract.Money;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The owner's limits, read and changed from the settings screen. Routes in contracts/desk-api.md. */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

	/** The same ceiling as an invoice total, far below what a bigint of cents can hold. */
	static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");

	private final PolicyRepository policies;

	private final Clock clock;

	public PolicyController(PolicyRepository policies, Clock clock) {
		this.policies = policies;
		this.clock = clock;
	}

	@GetMapping
	public Policy get() {
		return policies.get();
	}

	@PutMapping
	public Policy put(@RequestBody PolicyUpdate update) {
		if (update == null) {
			throw new InvalidPolicyException("The policy needs maxUnitPrice, approvalAbove and maxOrderTotal.");
		}
		Policy checked = new Policy(check("maxUnitPrice", update.maxUnitPrice()),
				check("approvalAbove", update.approvalAbove()), check("maxOrderTotal", update.maxOrderTotal()), null);
		return policies.save(checked, clock.instant());
	}

	static Money check(String name, Money value) {
		if (value == null) {
			throw new InvalidPolicyException(name + " is required.");
		}
		if (!Policy.CURRENCY.equals(value.currency())) {
			throw new InvalidPolicyException(name + " must be in " + Policy.CURRENCY + ".");
		}
		BigDecimal amount = value.amount();
		if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
			throw new InvalidPolicyException(name + " must be more than 0, in whole cents, and at most "
					+ MAX_AMOUNT.toPlainString() + ".");
		}
		return new Money(amount.setScale(2), value.currency());
	}

	public record PolicyUpdate(Money maxUnitPrice, Money approvalAbove, Money maxOrderTotal) {
	}

}
