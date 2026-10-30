package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.agent.RejectBody;
import com.tradedesk.desk.contract.AgentEnvelope;

/**
 * Checks a supplier's reply to one RFQ, or to one counter (S3), before the café shows anything: the right sender,
 * conversation and request, a body that fits the quote schema exactly, and numbers inside the contract's rules
 * (contracts/agent-messages.md). Anything else is dropped with a reason that is logged, never shown as an offer.
 */
public final class QuoteValidator {

	static final int MAX_SUPPLIER_NAME = 100;

	static final int MAX_NOTE = 200;

	static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("100000.00");

	/** numeric(12, 3) in the quote table. */
	static final BigDecimal MAX_AVAILABLE = new BigDecimal("999999999.999");

	private QuoteValidator() {
	}

	/**
	 * @param rfq the RFQ envelope this desk sent
	 * @param expectedFrom the peer's domain the RFQ went to
	 * @param request the café's checked request (the reply must not offer more, or in another unit)
	 */
	public static Outcome check(AgentJson json, String raw, AgentEnvelope rfq, String expectedFrom,
			PurchaseRequest request, Instant now, LocalDate today) {
		AgentEnvelope reply = raw == null ? null : json.envelope(raw).orElse(null);
		Dropped bad = header(reply, rfq, expectedFrom);
		if (bad != null) {
			return bad;
		}
		return switch (reply.type()) {
			case QUOTE -> quote(json, reply, rfq, request, now, today);
			case REJECT -> reject(json, reply, rfq);
			default -> new Dropped(reply, "a " + reply.type().wireName() + " is not a reply to an rfq");
		};
	}

	/**
	 * Checks a supplier's reply to a {@code counter} (S3): a revised quote that changes only what the contract lets it
	 * change (price no higher than before, stock, expiry, note), or a {@code reject} that leaves the quote as it was.
	 * @param counter the counter envelope this desk sent
	 * @param previous the quote as it stood before the counter
	 * @param ref the ids of that quote
	 */
	public static Outcome checkRevision(AgentJson json, String raw, AgentEnvelope counter, QuoteRow previous,
			NeedRepository.QuoteRef ref, Instant now) {
		AgentEnvelope reply = raw == null ? null : json.envelope(raw).orElse(null);
		Dropped bad = header(reply, counter, ref.supplierDomain());
		if (bad != null) {
			return bad;
		}
		return switch (reply.type()) {
			case QUOTE -> {
				QuoteBody revised = json.body(reply.body(), QuoteBody.class).orElse(null);
				if (revised == null) {
					yield new Dropped(reply, "the revised quote body doesn't match the quote schema");
				}
				String problem = revisionProblem(revised, previous, ref, now);
				yield problem == null ? new Accepted(reply, revised) : new Dropped(reply, problem);
			}
			case REJECT -> reject(json, reply, counter);
			default -> new Dropped(reply, "a " + reply.type().wireName() + " is not a reply to a counter");
		};
	}

	/** The checks every reply gets: an envelope, from the peer the request went to, in the same conversation. */
	private static Dropped header(AgentEnvelope reply, AgentEnvelope request, String expectedFrom) {
		if (reply == null) {
			return new Dropped(null, "the reply is not a valid agent envelope");
		}
		if (reply.messageId() == null || reply.type() == null || reply.body() == null || reply.sentAt() == null) {
			return new Dropped(reply, "the envelope is missing a required field");
		}
		if (!expectedFrom.equals(reply.from()) || !request.from().equals(reply.to())) {
			return new Dropped(reply, "the reply is not from " + expectedFrom + " to " + request.from());
		}
		if (!request.conversationId().equals(reply.conversationId())) {
			return new Dropped(reply, "the reply belongs to another conversation");
		}
		return null;
	}

	private static String revisionProblem(QuoteBody q, QuoteRow previous, NeedRepository.QuoteRef ref,
			Instant now) {
		if (!ref.quoteMessageId().equals(q.revises())) {
			return "revises doesn't name the countered quote";
		}
		if (!ref.rfqMessageId().equals(q.rfqId())) {
			return "rfqId doesn't match the RFQ";
		}
		if (q.supplierName() == null || !previous.supplier().name().equals(q.supplierName().strip())
				|| !Boolean.valueOf(previous.supplier().simulated()).equals(q.simulated())) {
			return "a revised quote can't change supplierName or simulated";
		}
		if (q.unitPrice() == null || !previous.unitPrice().currency().equals(q.unitPrice().currency())) {
			return "a revised quote must keep the currency " + previous.unitPrice().currency();
		}
		BigDecimal price = q.unitPrice().amount();
		if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 2
				|| price.compareTo(previous.unitPrice().amount()) > 0) {
			return "the revised unitPrice must be more than 0, in whole cents, and at most the old "
					+ previous.unitPrice().amount().toPlainString();
		}
		if (q.quantity() == null || q.quantity().compareTo(previous.quantity()) != 0 || q.unit() != previous.unit()
				|| !previous.deliveryDate().equals(q.deliveryDate())) {
			return "a revised quote can't change quantity, unit or deliveryDate";
		}
		if (q.availableQuantity() == null || q.availableQuantity().compareTo(q.quantity()) < 0
				|| q.availableQuantity().stripTrailingZeros().scale() > 3
				|| q.availableQuantity().compareTo(MAX_AVAILABLE) > 0) {
			return "availableQuantity must be at least quantity";
		}
		if (q.validUntil() == null || !q.validUntil().isAfter(now)) {
			return "validUntil must be in the future";
		}
		if (q.note() == null || q.note().length() > MAX_NOTE) {
			return "note must be at most " + MAX_NOTE + " characters";
		}
		return null;
	}

	private static Outcome quote(AgentJson json, AgentEnvelope reply, AgentEnvelope rfq, PurchaseRequest request,
			Instant now, LocalDate today) {
		QuoteBody quote = json.body(reply.body(), QuoteBody.class).orElse(null);
		if (quote == null) {
			return new Dropped(reply, "the quote body doesn't match the quote schema");
		}
		String problem = problem(quote, rfq, request, now, today);
		return problem == null ? new Accepted(reply, quote) : new Dropped(reply, problem);
	}

	private static String problem(QuoteBody q, AgentEnvelope rfq, PurchaseRequest request, Instant now,
			LocalDate today) {
		if (!rfq.messageId().equals(q.rfqId())) {
			return "rfqId doesn't match the RFQ";
		}
		if (q.revises() != null) {
			return "a quote that answers an RFQ can't revise another quote";
		}
		if (q.supplierName() == null || q.supplierName().isBlank() || q.supplierName().length() > MAX_SUPPLIER_NAME) {
			return "supplierName must be 1 to " + MAX_SUPPLIER_NAME + " characters";
		}
		if (q.simulated() == null) {
			return "simulated is required";
		}
		if (q.unitPrice() == null) {
			return "unitPrice is required";
		}
		if (!PurchaseRequestRules.CURRENCY.equals(q.unitPrice().currency())) {
			return "unitPrice must be in " + PurchaseRequestRules.CURRENCY + ", got " + q.unitPrice().currency();
		}
		BigDecimal price = q.unitPrice().amount();
		if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 2 || price.compareTo(MAX_UNIT_PRICE) > 0) {
			return "unitPrice must be more than 0, in whole cents, at most " + MAX_UNIT_PRICE.toPlainString();
		}
		if (q.quantity() == null || q.quantity().signum() <= 0 || q.quantity().stripTrailingZeros().scale() > 3
				|| q.quantity().compareTo(request.quantity()) > 0) {
			return "quantity must be more than 0 and at most the requested " + request.quantity().toPlainString();
		}
		if (q.availableQuantity() == null || q.availableQuantity().compareTo(q.quantity()) < 0
				|| q.availableQuantity().stripTrailingZeros().scale() > 3
				|| q.availableQuantity().compareTo(MAX_AVAILABLE) > 0) {
			return "availableQuantity must be at least quantity";
		}
		if (q.unit() != request.unit()) {
			return "unit must be " + request.unit().wireName();
		}
		if (q.deliveryDate() == null || q.deliveryDate().isBefore(today)) {
			return "deliveryDate must be today or later";
		}
		if (q.validUntil() == null || !q.validUntil().isAfter(now)) {
			return "validUntil must be in the future";
		}
		if (q.note() == null || q.note().length() > MAX_NOTE) {
			return "note must be at most " + MAX_NOTE + " characters";
		}
		return null;
	}

	/** A {@code reject} of {@code request} (an RFQ or, from S3, any request this desk sent). */
	public static Outcome reject(AgentJson json, AgentEnvelope reply, AgentEnvelope request) {
		RejectBody reject = json.body(reply.body(), RejectBody.class).orElse(null);
		if (reject == null || !request.messageId().equals(reject.rfqId()) || reject.reason() == null
				|| reject.reason().isBlank() || reject.reason().length() > RejectBody.MAX_REASON) {
			return new Dropped(reply, "the reject body doesn't match the reject schema or the request");
		}
		return new Rejected(reply, reject);
	}

	public sealed interface Outcome permits Accepted, Rejected, Dropped {
	}

	public record Accepted(AgentEnvelope envelope, QuoteBody quote) implements Outcome {
	}

	public record Rejected(AgentEnvelope envelope, RejectBody reject) implements Outcome {
	}

	/** @param envelope the reply if it could at least be read as an envelope, else null */
	public record Dropped(AgentEnvelope envelope, String problem) implements Outcome {
	}

}
