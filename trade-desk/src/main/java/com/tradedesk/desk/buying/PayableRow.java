package com.tradedesk.desk.buying;

import java.time.Instant;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.invoice.InvoiceStatus;
import com.tradedesk.desk.need.QuoteRow;

/**
 * An invoice this desk has to pay: what {@code GET /api/payables} lists and {@code payable.updated} carries. Shape in
 * contracts/desk-api.md (Payable row).
 *
 * @param description written by the café from its own need, never taken from the supplier
 * @param payerViewUrl always a {@code https://www.sandbox.paypal.com/} link; checked before it was stored
 * @param status the supplier's last report
 */
public record PayableRow(UUID id, UUID needId, QuoteRow.Supplier supplier, String description,
		String paypalInvoiceId, String payerViewUrl, Money total, InvoiceStatus status, Instant createdAt,
		Instant updatedAt) {

}
