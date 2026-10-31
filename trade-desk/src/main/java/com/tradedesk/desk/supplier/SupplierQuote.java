package com.tradedesk.desk.supplier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.tradedesk.desk.contract.Unit;

/**
 * A quote this supplier desk sent, as it sent it ({@code supplier_quote}). A counter or an accept names it by
 * {@code messageId}, and is checked against this record, never against what the buyer claims it said.
 *
 * @param revises the quote this one replaced after a counter, or null
 * @param invoiceId the invoice created when it was accepted, or null
 */
public record SupplierQuote(UUID messageId, UUID conversationId, String buyerDomain, UUID rfqId, String sku,
		BigDecimal unitPrice, String currency, BigDecimal quantity, BigDecimal availableQuantity, Unit unit,
		LocalDate deliveryDate, Instant validUntil, String note, UUID revises, Status status, UUID invoiceId) {

	public enum Status {

		/** Can be countered (once, if it isn't a revision itself) or accepted. */
		OPEN,

		/** Replaced by a revised quote after a counter; can't be accepted. */
		SUPERSEDED,

		/** Accepted and invoiced. */
		ACCEPTED

	}

}
