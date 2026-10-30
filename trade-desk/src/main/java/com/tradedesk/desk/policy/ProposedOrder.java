package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;

/**
 * What the café is about to accept, as Java computed it from the stored quote: the input to {@link PolicyEngine}.
 * Nothing in it comes from an LLM.
 *
 * @param unit the unit of {@code unitPrice} and {@code quantity}
 * @param requestedQuantity what the need asked for; an offer of less is a partial one
 * @param needMaxUnitPrice the need's own budget per {@code unit}, or null
 */
public record ProposedOrder(String supplierDomain, Money unitPrice,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity, Unit unit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requestedQuantity, Money total, Instant validUntil,
		Money needMaxUnitPrice) {

}
