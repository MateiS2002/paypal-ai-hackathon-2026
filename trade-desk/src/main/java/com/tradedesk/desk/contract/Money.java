package com.tradedesk.desk.contract;

import java.math.BigDecimal;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Money on the wire and in code: {@code {"amount": "12.50", "currency": "USD"}}. The amount is a decimal string in
 * JSON and a {@link BigDecimal} in Java, never a float. Schema: contracts/money.schema.json.
 */
public record Money(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currency) {

	public Money {
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(currency, "currency");
		// Matches the schema's pattern: plain notation, at most 4 decimal places.
		if (amount.scale() < 0 || amount.scale() > 4) {
			throw new IllegalArgumentException("amount must have 0 to 4 decimal places, got " + amount);
		}
		if (!currency.matches("[A-Z]{3}")) {
			throw new IllegalArgumentException("currency must be an ISO 4217 code, got " + currency);
		}
	}

}
