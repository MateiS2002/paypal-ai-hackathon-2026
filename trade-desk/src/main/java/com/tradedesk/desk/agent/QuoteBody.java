package com.tradedesk.desk.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;

/**
 * The body of a {@code quote} (contracts/agent-messages.md). Every number is set by the supplier's Java code;
 * {@code note} is the only text its LLM writes, and the buyer never reads numbers from it.
 *
 * @param revises null on a quote that answers an RFQ; on a revised quote (S3), the {@code quoteId} of the counter
 */
public record QuoteBody(UUID rfqId, String supplierName, Boolean simulated, Money unitPrice,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableQuantity, Unit unit, LocalDate deliveryDate,
		Instant validUntil, String note, UUID revises) {

}
