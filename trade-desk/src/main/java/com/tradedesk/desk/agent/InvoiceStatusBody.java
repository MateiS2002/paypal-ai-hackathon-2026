package com.tradedesk.desk.agent;

import java.time.Instant;

import com.tradedesk.desk.invoice.InvoiceStatus;

/**
 * The body of an {@code invoice.status} (contracts/agent-messages.md): the supplier's push when PayPal reports a new
 * status for an invoice it sent after an accept. The buyer's reply carries the status it recorded.
 */
public record InvoiceStatusBody(String paypalInvoiceId, InvoiceStatus status, Instant updatedAt) {

}
