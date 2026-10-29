package com.tradedesk.desk.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tradedesk.desk.contract.Money;

/**
 * One ledger row: what {@code GET /api/invoices} lists and {@code invoice.updated} carries. Shape documented in
 * contracts/desk-api.md.
 */
public record InvoiceRow(UUID id, String invoiceNumber, NewInvoice.Customer customer, List<Line> lines, Money total,
		InvoiceStatus status, InvoiceSource source, LocalDate dueDate, String payerViewUrl, Instant createdAt,
		Instant updatedAt, Instant paidAt) {

	public record Line(String description, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
			Money unitPrice, Money lineTotal) {
	}

}
