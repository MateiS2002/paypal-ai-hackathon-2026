package com.tradedesk.desk.supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.AgentReplies;
import com.tradedesk.desk.agent.CounterBody;
import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * The seller side of a {@code counter} (S3). The countered quote is looked up in this desk's own records, never taken
 * from the message; {@link CounterPricer} decides the new price in Java against today's floor; the answer is a revised
 * {@code quote} with only the price, stock, expiry and note changed, or a {@code reject} that leaves the quote as it
 * was. No LLM is involved: the note is a template. One counter per quote: a revised quote can't be countered again.
 *
 * <p>TODO S5: {@code from} is only claimed until S5. Require a registry token with scope {@code quote:counter} and
 * aud = this desk, and a signed envelope whose {@code from} matches it, before trusting the buyer check below.
 */
@Component
public class CounterHandler {

	private static final Logger log = LoggerFactory.getLogger(CounterHandler.class);

	private final SupplierQuoteRepository quotes;

	private final CatalogueRepository catalogue;

	private final AgentReplies replies;

	private final AgentJson json;

	private final DeskProperties desk;

	private final Clock clock;

	public CounterHandler(SupplierQuoteRepository quotes, CatalogueRepository catalogue, AgentReplies replies,
			AgentJson json, DeskProperties desk, Clock clock) {
		this.quotes = quotes;
		this.catalogue = catalogue;
		this.replies = replies;
		this.json = json;
		this.desk = desk;
		this.clock = clock;
	}

	public AgentEnvelope answer(AgentEnvelope counter) {
		Persona persona = Persona.fromWire(desk.persona());
		if (persona == null) {
			return replies.reject(counter, "This desk doesn't sell anything.");
		}
		CounterBody body = json.body(counter.body(), CounterBody.class).orElse(null);
		if (body == null || body.quoteId() == null || body.unitPrice() == null) {
			return replies.reject(counter, "The counter body doesn't match the counter schema.");
		}
		SupplierQuote quote = quotes.find(body.quoteId())
			.filter(q -> q.buyerDomain().equals(counter.from()) && q.conversationId().equals(counter.conversationId()))
			.orElse(null);
		if (quote == null) {
			return replies.reject(counter, "We sent you no quote " + body.quoteId() + " in this conversation.");
		}
		Instant now = clock.instant();
		String refusal = refusal(quote, body.unitPrice(), now);
		if (refusal != null) {
			return reject(counter, refusal);
		}
		CatalogueItem item = catalogue.find(quote.sku()).orElse(null);
		if (item == null) {
			return reject(counter, "We no longer sell this item. Ask us for a new quote.");
		}
		if (item.floorPrice().compareTo(quote.unitPrice()) > 0) {
			return reject(counter, "Our prices have changed since this quote. Ask us for a new quote.");
		}
		if (item.stock().compareTo(quote.quantity()) < 0) {
			return reject(counter, "We no longer have " + quote.quantity().stripTrailingZeros().toPlainString() + " "
					+ quote.unit().wireName() + " in stock. Ask us for a new quote.");
		}

		BigDecimal target = body.unitPrice().amount();
		BigDecimal revised = CounterPricer.revise(quote.unitPrice(), item.floorPrice(), target,
				persona.maxConcession());
		QuoteBody revision = new QuoteBody(quote.rfqId(), desk.name(), desk.simulated(),
				new Money(revised, quote.currency()), quote.quantity(), item.stock().setScale(3, RoundingMode.DOWN),
				quote.unit(), quote.deliveryDate(), now.plus(RfqHandler.QUOTE_VALID_FOR),
				note(revised, target, quote), quote.messageId());
		AgentEnvelope reply = replies.reply(counter, AgentEnvelope.Type.QUOTE, revision);
		try {
			quotes.revise(new SupplierQuote(reply.messageId(), quote.conversationId(), quote.buyerDomain(),
					quote.rfqId(), quote.sku(), revised, quote.currency(), quote.quantity(),
					revision.availableQuantity(), quote.unit(), quote.deliveryDate(), revision.validUntil(),
					revision.note(), quote.messageId(), SupplierQuote.Status.OPEN, null));
		}
		catch (DuplicateKeyException ex) {
			// Another counter for the same quote got here first.
			return reject(counter, "We already answered a counter on this quote; there is one counter round.");
		}
		log.info("Answered a counter from {} on quote {}: target {}, revised {} (was {})", counter.from(),
				quote.messageId(), target, revised, quote.unitPrice());
		return reply;
	}

	/** Why this counter can't be answered with a revised quote, or null if it can. */
	private static String refusal(SupplierQuote quote, Money target, Instant now) {
		if (quote.revises() != null || quote.status() == SupplierQuote.Status.SUPERSEDED) {
			return "We already answered a counter on this quote; there is one counter round.";
		}
		if (quote.status() == SupplierQuote.Status.ACCEPTED) {
			return "This quote was already accepted.";
		}
		if (!quote.validUntil().isAfter(now)) {
			return "This quote expired at " + quote.validUntil() + ". Ask us for a new quote.";
		}
		BigDecimal amount = target.amount();
		if (!quote.currency().equals(target.currency()) || amount.signum() <= 0
				|| amount.stripTrailingZeros().scale() > 2) {
			return "The counter price must be more than 0, in whole cents and in " + quote.currency() + ".";
		}
		return null;
	}

	/** A template, not the LLM: the note on a revised quote only restates Java's numbers. */
	static String note(BigDecimal revised, BigDecimal target, SupplierQuote quote) {
		String per = " " + quote.currency() + " per " + quote.unit().wireName();
		if (revised.compareTo(quote.unitPrice()) >= 0) {
			return "Our price of " + quote.unitPrice().toPlainString() + per + " holds.";
		}
		if (revised.compareTo(target) <= 0) {
			return "We can meet " + revised.toPlainString() + per + ".";
		}
		return "Best we can do is " + revised.toPlainString() + per + " (was " + quote.unitPrice().toPlainString()
				+ ").";
	}

	private AgentEnvelope reject(AgentEnvelope counter, String reason) {
		log.info("Refused a counter from {}: {}", counter.from(), reason);
		return replies.reject(counter, reason);
	}

}
