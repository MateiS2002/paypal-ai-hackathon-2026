package com.tradedesk.desk.supplier;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.tradedesk.desk.agent.AcceptBody;
import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.AgentReplies;
import com.tradedesk.desk.agent.AgentUnavailableException;
import com.tradedesk.desk.agent.InvoiceBody;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.invoice.IdempotencyConflictException;
import com.tradedesk.desk.invoice.InvalidInvoiceException;
import com.tradedesk.desk.invoice.InvoiceRepository;
import com.tradedesk.desk.invoice.InvoiceRow;
import com.tradedesk.desk.invoice.InvoiceService;
import com.tradedesk.desk.invoice.InvoiceSource;
import com.tradedesk.desk.invoice.NegotiationLink;
import com.tradedesk.desk.invoice.NewInvoice;
import com.tradedesk.desk.paypal.PayPalException;
import com.tradedesk.desk.paypal.PayPalNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * The seller side of an {@code accept} (S3). Before any PayPal call, Java re-checks the quote against this desk's own
 * records: it is this buyer's, still the latest (not replaced by a revision), still within {@code validUntil}, still at
 * or above today's floor and still in stock. Only then does S1's {@link InvoiceService} create and send the invoice,
 * with {@code source = NEGOTIATION} and the conversation linked. Anything else gets a {@code reject} and no invoice.
 *
 * <p>The invoice's idempotency key is derived from the quote, so accepting the same quote again, with any message id,
 * returns the same invoice: one quote never gets two.
 *
 * <p>TODO S5: {@code from} is only claimed until S5, so a local process could accept a quote it asked for itself.
 * Require a registry token with scope {@code quote:accept} and aud = this desk, and a signed envelope whose
 * {@code from} matches it, before creating any invoice.
 */
@Component
public class AcceptHandler {

	private static final Logger log = LoggerFactory.getLogger(AcceptHandler.class);

	private final SupplierQuoteRepository quotes;

	private final CatalogueRepository catalogue;

	private final InvoiceService invoices;

	private final InvoiceRepository invoiceRecords;

	private final AgentReplies replies;

	private final AgentJson json;

	private final DeskProperties desk;

	private final Clock clock;

	public AcceptHandler(SupplierQuoteRepository quotes, CatalogueRepository catalogue, InvoiceService invoices,
			InvoiceRepository invoiceRecords, AgentReplies replies, AgentJson json, DeskProperties desk, Clock clock) {
		this.quotes = quotes;
		this.catalogue = catalogue;
		this.invoices = invoices;
		this.invoiceRecords = invoiceRecords;
		this.replies = replies;
		this.json = json;
		this.desk = desk;
		this.clock = clock;
	}

	public AgentEnvelope answer(AgentEnvelope accept) {
		if (Persona.fromWire(desk.persona()) == null) {
			return replies.reject(accept, "This desk doesn't sell anything.");
		}
		AcceptBody body = json.body(accept.body(), AcceptBody.class).orElse(null);
		if (body == null || body.quoteId() == null || body.billTo() == null) {
			return replies.reject(accept, "The accept body doesn't match the accept schema.");
		}
		SupplierQuote quote = quotes.find(body.quoteId())
			.filter(q -> q.buyerDomain().equals(accept.from()) && q.conversationId().equals(accept.conversationId()))
			.orElse(null);
		if (quote == null) {
			return replies.reject(accept, "We sent you no quote " + body.quoteId() + " in this conversation.");
		}
		if (quote.status() == SupplierQuote.Status.ACCEPTED && quote.invoiceId() != null) {
			// Accepted before, perhaps under another message id: the same invoice again, never a second one.
			return invoiceReply(accept, quote.invoiceId());
		}
		String refusal = refusal(quote);
		if (refusal != null) {
			log.info("Refused an accept from {} for quote {}: {}", accept.from(), quote.messageId(), refusal);
			return replies.reject(accept, refusal);
		}
		CatalogueItem item = catalogue.find(quote.sku()).orElseThrow();

		NewInvoice invoice = new NewInvoice(new NewInvoice.Customer(body.billTo().name(), body.billTo().email()),
				List.of(new NewInvoice.Line(item.description() + ", " + quote.unit().wireName(), quote.quantity(),
						new Money(quote.unitPrice(), quote.currency()))));
		InvoiceRow row;
		try {
			row = invoices
				.create(invoiceKey(quote.messageId()), invoice, InvoiceSource.NEGOTIATION, null,
						new NegotiationLink(quote.conversationId(), quote.buyerDomain()))
				.invoice();
		}
		catch (InvalidInvoiceException ex) {
			return replies.reject(accept, ex.getMessage());
		}
		catch (IdempotencyConflictException ex) {
			return replies.reject(accept, "This quote is already being invoiced to someone else.");
		}
		catch (PayPalNotConfiguredException ex) {
			log.warn("Can't invoice quote {}: PayPal is not configured on this desk", quote.messageId());
			throw new AgentUnavailableException("This desk can't send invoices right now. Try again later.");
		}
		catch (PayPalException ex) {
			if (!isTransient(ex)) {
				return replies.reject(accept, "We couldn't send the invoice: " + ex.getMessage());
			}
			// Not a reject, which would be stored as the final answer: the buyer retries, and since the progress is
			// stored, accepting the same quote again resumes the same invoice.
			log.warn("Can't invoice quote {} yet: {}", quote.messageId(), ex.getMessage());
			throw new AgentUnavailableException("PayPal didn't take the invoice yet. Try again later.");
		}
		quotes.accept(quote.messageId(), row.id());
		// The invoice reply tells the buyer this status; the poller only pushes later changes.
		invoiceRecords.markBuyerNotified(row.id(), row.status());
		log.info("Accepted quote {} from {}: invoice {} for {} {}", quote.messageId(), accept.from(), row.id(),
				row.total().amount(), row.total().currency());
		return invoiceReply(accept, row.id());
	}

	/** PayPal unreachable, rate-limited or failing on its side: worth a retry, unlike a refusal or a wrong total. */
	private static boolean isTransient(PayPalException ex) {
		return ex.status() == 0 || ex.status() == 429 || ex.status() >= 500;
	}

	/** Why this quote can't be accepted, checked against this desk's records and today's catalogue; null if it can. */
	private String refusal(SupplierQuote quote) {
		if (quote.status() == SupplierQuote.Status.SUPERSEDED) {
			return "This quote was replaced by a revised one after your counter. Accept the revised quote.";
		}
		if (!quote.validUntil().isAfter(clock.instant())) {
			return "This quote expired at " + quote.validUntil() + ". Ask us for a new quote.";
		}
		CatalogueItem item = catalogue.find(quote.sku()).orElse(null);
		if (item == null) {
			return "We no longer sell this item. Ask us for a new quote.";
		}
		if (quote.unitPrice().compareTo(item.floorPrice()) < 0) {
			return "Our prices have changed since this quote. Ask us for a new quote.";
		}
		if (item.stock().compareTo(quote.quantity()) < 0) {
			return "We no longer have " + quote.quantity().stripTrailingZeros().toPlainString() + " "
					+ quote.unit().wireName() + " in stock. Ask us for a new quote.";
		}
		return null;
	}

	private AgentEnvelope invoiceReply(AgentEnvelope accept, UUID invoiceId) {
		InvoiceRepository.StoredInvoice stored = invoiceRecords.find(invoiceId).orElseThrow();
		InvoiceRow row = stored.row();
		return replies.reply(accept, AgentEnvelope.Type.INVOICE,
				new InvoiceBody(stored.payPalInvoiceId(), row.payerViewUrl(), row.total(), row.status()));
	}

	/** One idempotency key per quote, so every accept of it resumes the same invoice. */
	static UUID invoiceKey(UUID quoteMessageId) {
		return UUID.nameUUIDFromBytes(("accept:" + quoteMessageId).getBytes(StandardCharsets.UTF_8));
	}

}
