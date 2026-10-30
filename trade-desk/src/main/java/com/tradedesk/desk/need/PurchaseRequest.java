package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;

/**
 * What the café wants to buy: the body of {@code POST /api/needs}, and what the buyer agent proposes. Not trusted as
 * is, from the LLM or from the browser: {@link PurchaseRequestRules#check} checks every field. Shape documented in
 * contracts/desk-api.md.
 *
 * @param maxUnitPrice the café's budget per unit, or null; never sent to suppliers
 */
public record PurchaseRequest(String product, String origin,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity, Unit unit, LocalDate deliverBy,
		Priority priority, Money maxUnitPrice) {

}
