package com.tradedesk.desk.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.tradedesk.desk.contract.Money;

/**
 * The money rules for an invoice, in Java and never in a prompt: checks a {@link NewInvoice} and prices it.
 *
 * <p>Each line total is {@code quantity × unit price} rounded {@code HALF_UP} to the cent; the invoice total is the
 * sum of the rounded line totals. PayPal gets the same quantity and unit price per line, so both totals agree to the
 * cent (the service checks that before sending).
 */
public final class InvoiceTotals {

	/** Currencies with cents that PayPal invoices in. Others are refused rather than rounded wrongly. */
	public static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "CAD", "AUD");

	static final int MAX_LINES = 50;

	static final int MAX_DESCRIPTION = 200;

	static final int MAX_NAME = 100;

	/** numeric(10, 3) in the invoice_line table. */
	private static final BigDecimal MAX_QUANTITY = new BigDecimal("9999999.999");

	/** Keeps every amount far below what a bigint of cents can hold. */
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}");

	private InvoiceTotals() {
	}

	/**
	 * Checks the request and prices every line.
	 * @throws InvalidInvoiceException with a message for the user if any rule is broken
	 */
	public static Priced price(NewInvoice invoice) {
		if (invoice == null || invoice.customer() == null) {
			throw new InvalidInvoiceException("The invoice needs a customer.");
		}
		String name = trimmed(invoice.customer().name());
		String email = trimmed(invoice.customer().email()).toLowerCase(Locale.ROOT);
		if (name.isEmpty() || name.length() > MAX_NAME) {
			throw new InvalidInvoiceException("The customer name must be 1 to " + MAX_NAME + " characters.");
		}
		if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
			throw new InvalidInvoiceException("The customer email is not a valid email address.");
		}
		List<NewInvoice.Line> lines = invoice.lines();
		if (lines == null || lines.isEmpty()) {
			throw new InvalidInvoiceException("The invoice needs at least one line.");
		}
		if (lines.size() > MAX_LINES) {
			throw new InvalidInvoiceException("An invoice can have at most " + MAX_LINES + " lines.");
		}

		String currency = null;
		BigDecimal total = BigDecimal.ZERO.setScale(2);
		List<PricedLine> priced = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			NewInvoice.Line line = lines.get(i);
			String where = "Line " + (i + 1) + ": ";
			if (line == null) {
				throw new InvalidInvoiceException(where + "is empty.");
			}
			String description = trimmed(line.description());
			if (description.isEmpty() || description.length() > MAX_DESCRIPTION) {
				throw new InvalidInvoiceException(where + "the description must be 1 to " + MAX_DESCRIPTION
						+ " characters.");
			}
			BigDecimal quantity = line.quantity();
			if (quantity == null || quantity.signum() <= 0) {
				throw new InvalidInvoiceException(where + "the quantity must be more than zero.");
			}
			if (quantity.stripTrailingZeros().scale() > 3 || quantity.compareTo(MAX_QUANTITY) > 0) {
				throw new InvalidInvoiceException(where + "the quantity can have at most 3 decimal places and must be "
						+ "below 10,000,000.");
			}
			Money unitPrice = line.unitPrice();
			if (unitPrice == null || unitPrice.amount().signum() <= 0) {
				throw new InvalidInvoiceException(where + "the unit price must be more than zero.");
			}
			if (unitPrice.amount().stripTrailingZeros().scale() > 2 || unitPrice.amount().compareTo(MAX_AMOUNT) > 0) {
				throw new InvalidInvoiceException(where + "the unit price must be in whole cents and at most "
						+ MAX_AMOUNT.toPlainString() + ".");
			}
			if (!CURRENCIES.contains(unitPrice.currency())) {
				throw new InvalidInvoiceException(where + "currency " + unitPrice.currency() + " is not supported.");
			}
			if (currency == null) {
				currency = unitPrice.currency();
			}
			else if (!currency.equals(unitPrice.currency())) {
				throw new InvalidInvoiceException("All lines must use the same currency.");
			}

			BigDecimal qty = quantity.setScale(3);
			BigDecimal price = unitPrice.amount().setScale(2);
			BigDecimal lineTotal = lineTotal(qty, price);
			if (lineTotal.signum() <= 0) {
				throw new InvalidInvoiceException(where + "the line total rounds to zero.");
			}
			priced.add(new PricedLine(description, qty, price, lineTotal));
			total = total.add(lineTotal);
		}
		if (total.compareTo(MAX_AMOUNT) > 0) {
			throw new InvalidInvoiceException("The invoice total must be at most " + MAX_AMOUNT.toPlainString() + ".");
		}
		return new Priced(new NewInvoice.Customer(name, email), List.copyOf(priced), total, currency);
	}

	/** {@code quantity × unitPrice}, rounded {@code HALF_UP} to the cent. */
	static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice) {
		return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
	}

	/** Amount in cents, for the database. */
	public static long toMinor(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
	}

	public static BigDecimal fromMinor(long minor) {
		return BigDecimal.valueOf(minor, 2);
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.strip();
	}

	/** A checked and priced line: quantity has scale 3, money has scale 2. */
	public record PricedLine(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
	}

	/** A checked and priced invoice. The customer's email is lower-case. */
	public record Priced(NewInvoice.Customer customer, List<PricedLine> lines, BigDecimal total, String currency) {
	}

}
