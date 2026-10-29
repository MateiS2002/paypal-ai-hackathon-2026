package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.tradedesk.desk.contract.Money;

/**
 * The rules for a purchase request, in Java and never in a prompt. Used on the LLM's proposal (field by field, so a
 * bad field becomes a question for the owner) and again on what the browser posts (all or nothing).
 */
public final class PurchaseRequestRules {

	static final int MAX_PRODUCT = 100;

	static final int MAX_ORIGIN = 60;

	static final BigDecimal MAX_QUANTITY = new BigDecimal("100000");

	static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("100000.00");

	/** S2 quotes and budgets are in USD only. */
	public static final String CURRENCY = "USD";

	private PurchaseRequestRules() {
	}

	/** Trimmed product, or null if it breaks the rules. */
	static String product(String value) {
		String product = trimmed(value);
		return product == null || product.length() > MAX_PRODUCT ? null : product;
	}

	/** Trimmed origin, or null for "any origin" (also when it breaks the rules). */
	static String origin(String value) {
		String origin = trimmed(value);
		return origin == null || origin.length() > MAX_ORIGIN ? null : origin;
	}

	/** The quantity with scale 3, or null if it breaks the rules. */
	static BigDecimal quantity(BigDecimal value) {
		if (value == null || value.signum() <= 0 || value.compareTo(MAX_QUANTITY) > 0
				|| value.stripTrailingZeros().scale() > 3) {
			return null;
		}
		return value.setScale(3);
	}

	/** The date if it is after today in the desk's time zone, else null. */
	static LocalDate deliverBy(LocalDate value, LocalDate today) {
		return value != null && value.isAfter(today) ? value : null;
	}

	/** A USD budget in whole cents, or null if it breaks the rules. */
	static Money maxUnitPrice(Money value) {
		if (value == null || !CURRENCY.equals(value.currency()) || value.amount().signum() <= 0
				|| value.amount().compareTo(MAX_UNIT_PRICE) > 0 || value.amount().stripTrailingZeros().scale() > 2) {
			return null;
		}
		return new Money(value.amount().setScale(2), value.currency());
	}

	/** The required fields that are still null, in the order the owner reads them. */
	static List<String> missing(PurchaseRequest request) {
		List<String> missing = new ArrayList<>();
		if (request.product() == null) {
			missing.add("product");
		}
		if (request.quantity() == null) {
			missing.add("quantity");
		}
		if (request.unit() == null) {
			missing.add("unit");
		}
		if (request.deliverBy() == null) {
			missing.add("deliverBy");
		}
		return missing;
	}

	/**
	 * Checks a request the owner confirmed and returns it normalised (trimmed text, quantity scale 3, money scale 2).
	 * @throws InvalidNeedException naming the first broken rule
	 */
	public static PurchaseRequest check(PurchaseRequest request, LocalDate today) {
		if (request == null) {
			throw new InvalidNeedException("The request is empty.");
		}
		String product = product(request.product());
		if (product == null) {
			throw new InvalidNeedException("The product must be 1 to " + MAX_PRODUCT + " characters.");
		}
		if (request.origin() != null && !request.origin().isBlank() && origin(request.origin()) == null) {
			throw new InvalidNeedException("The origin must be at most " + MAX_ORIGIN + " characters.");
		}
		BigDecimal quantity = quantity(request.quantity());
		if (quantity == null) {
			throw new InvalidNeedException("The quantity must be more than 0 and at most "
					+ MAX_QUANTITY.toPlainString() + ", with at most 3 decimal places.");
		}
		if (request.unit() == null) {
			throw new InvalidNeedException("The unit must be kg or lb.");
		}
		if (deliverBy(request.deliverBy(), today) == null) {
			throw new InvalidNeedException("The delivery date must be after today.");
		}
		if (request.priority() == null) {
			throw new InvalidNeedException("The priority must be SPEED, PRICE or BALANCED.");
		}
		Money maxUnitPrice = null;
		if (request.maxUnitPrice() != null) {
			maxUnitPrice = maxUnitPrice(request.maxUnitPrice());
			if (maxUnitPrice == null) {
				throw new InvalidNeedException("The maximum unit price must be in USD, more than 0, in whole cents.");
			}
		}
		return new PurchaseRequest(product, origin(request.origin()), quantity, request.unit(), request.deliverBy(),
				request.priority(), maxUnitPrice);
	}

	private static String trimmed(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}

}
