package com.tradedesk.desk.agent;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tradedesk.desk.contract.Unit;

/**
 * The body of an {@code rfq} (contracts/agent-messages.md). Deliberately has no field for the buyer's budget, stock
 * or priority, so none of them can leak to a supplier.
 */
public record RfqBody(String product, String origin, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
		Unit unit, LocalDate deliverBy) {

}
