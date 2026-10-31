package com.tradedesk.desk.agent;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.invoice.InvoiceStatus;

/**
 * The body of an {@code invoice}, the supplier's reply to an {@code accept} (contracts/agent-messages.md). The buyer
 * checks the link and the total before it records anything.
 */
public record InvoiceBody(String paypalInvoiceId, String payerViewUrl, Money total, InvoiceStatus status) {

}
