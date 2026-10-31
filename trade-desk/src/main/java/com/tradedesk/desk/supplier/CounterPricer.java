package com.tradedesk.desk.supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How a supplier answers a counter, in Java and never in a prompt: it meets the buyer's target if it can, moves part of
 * the way if the target is below what it will give, and holds otherwise. Two limits, both checked here:
 * <ul>
 * <li>the persona's {@link Persona#maxConcession()}: the most it takes off its quoted price in one counter;</li>
 * <li>the item's floor price, which it never goes below, whatever the buyer asks.</li>
 * </ul>
 */
public final class CounterPricer {

	private CounterPricer() {
	}

	/**
	 * @param quoted the unit price of the quote being countered
	 * @param floor the item's floor price today
	 * @param target the buyer's target
	 * @param maxConcession the most to take off {@code quoted}, as a fraction (0.06 is 6%)
	 * @return the revised unit price, in cents: between the target and {@code quoted}, and never below {@code floor}
	 */
	public static BigDecimal revise(BigDecimal quoted, BigDecimal floor, BigDecimal target, BigDecimal maxConcession) {
		BigDecimal price = quoted.setScale(2, RoundingMode.UNNECESSARY);
		if (target.compareTo(price) >= 0) {
			return price;
		}
		// Rounded up, so the concession is never more than the persona allows.
		BigDecimal lowest = price.multiply(BigDecimal.ONE.subtract(maxConcession)).setScale(2, RoundingMode.CEILING)
			.max(floor.setScale(2, RoundingMode.CEILING));
		BigDecimal revised = target.setScale(2, RoundingMode.CEILING).max(lowest).min(price);
		if (revised.compareTo(floor) < 0) {
			// Unreachable unless the code above is wrong: fail loudly rather than sell below the floor.
			throw new IllegalStateException("A counter answer of " + revised + " would go below the floor " + floor);
		}
		return revised;
	}

}
