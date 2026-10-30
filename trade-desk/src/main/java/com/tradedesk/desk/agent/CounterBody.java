package com.tradedesk.desk.agent;

import java.util.UUID;

import com.tradedesk.desk.contract.Money;

/**
 * The body of a {@code counter} (contracts/agent-messages.md): the buyer's target price for one quote. The buyer's
 * Java code has already clamped it; the supplier decides in Java whether to meet it, and never goes below its floor.
 *
 * @param quoteId the {@code messageId} of the supplier's quote being countered
 */
public record CounterBody(UUID quoteId, Money unitPrice) {

}
