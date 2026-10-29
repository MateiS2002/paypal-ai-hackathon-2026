package com.tradedesk.desk.invoice;

import java.math.BigDecimal;
import java.util.List;

import com.tradedesk.desk.contract.Money;

/**
 * What to invoice: the body of {@code POST /api/invoices}, and what S3 and S7 pass to {@link InvoiceService}. Not
 * trusted as is: {@link InvoiceTotals#price} checks every field before anything is stored or sent.
 */
public record NewInvoice(Customer customer, List<Line> lines) {

	public record Customer(String name, String email) {
	}

	public record Line(String description, BigDecimal quantity, Money unitPrice) {
	}

}
