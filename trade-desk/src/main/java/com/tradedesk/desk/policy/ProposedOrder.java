package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tradedesk.desk.contract.Money;

/**
 * What the café is about to accept, as Java computed it from the stored quote: the input to {@link PolicyEngine}.
 * Nothing in it comes from an LLM.
 *
 * @param needMaxUnitPrice the need's own budget per unit, or null
 */
public record ProposedOrder(String supplierDomain, Money unitPrice,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity, Money total, Instant validUntil,
		Money needMaxUnitPrice) {

}
