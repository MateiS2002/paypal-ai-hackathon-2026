package com.tradedesk.desk.buying;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.InvoiceBody;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.need.QuoteValidator;

/**
 * Checks a supplier's answer to an {@code accept} before the café records anything (contracts/agent-messages.md): the
 * right sender and conversation, an {@code invoice} body that fits exactly, a PayPal sandbox pay link and nothing
 * else, and the total the café agreed to, to the cent. A supplier can't send the owner to a look-alike page or bill
 * more than the accepted quote.
 */
public final class InvoiceReplyCheck {

	static final Pattern PAYPAL_INVOICE_ID = Pattern.compile("[A-Za-z0-9-]{1,100}");

	/** The only host a pay link may point to. */
	static final String PAY_HOST = "www.sandbox.paypal.com";

	private InvoiceReplyCheck() {
	}

	/**
	 * @param accept the accept envelope this desk sent
	 * @param supplierDomain the supplier it went to
	 * @param agreedTotal the accepted quote's {@code quantity × unitPrice}, as Java computed it
	 */
	public static Outcome check(AgentJson json, String raw, AgentEnvelope accept, String supplierDomain,
			Money agreedTotal) {
		AgentEnvelope reply = raw == null ? null : json.envelope(raw).orElse(null);
		if (reply == null || reply.messageId() == null || reply.type() == null || reply.body() == null) {
			return new Dropped(reply, "the reply is not a complete agent envelope");
		}
		if (!supplierDomain.equals(reply.from()) || !accept.from().equals(reply.to())
				|| !accept.conversationId().equals(reply.conversationId())) {
			return new Dropped(reply, "the reply is not from " + supplierDomain + " in this conversation");
		}
		return switch (reply.type()) {
			case INVOICE -> invoice(json, reply, agreedTotal);
			case REJECT -> switch (QuoteValidator.reject(json, reply, accept)) {
				case QuoteValidator.Rejected rejected -> new Refused(reply, rejected.reject().reason());
				case QuoteValidator.Dropped dropped -> new Dropped(reply, dropped.problem());
				case QuoteValidator.Accepted accepted -> throw new IllegalStateException("a reject is never a quote");
			};
			default -> new Dropped(reply, "a " + reply.type().wireName() + " is not a reply to an accept");
		};
	}

	private static Outcome invoice(AgentJson json, AgentEnvelope reply, Money agreedTotal) {
		InvoiceBody invoice = json.body(reply.body(), InvoiceBody.class).orElse(null);
		if (invoice == null || invoice.status() == null || invoice.total() == null) {
			return new Dropped(reply, "the invoice body doesn't match the invoice schema");
		}
		if (invoice.paypalInvoiceId() == null || !PAYPAL_INVOICE_ID.matcher(invoice.paypalInvoiceId()).matches()) {
			return new Dropped(reply, "paypalInvoiceId is not a PayPal invoice id");
		}
		if (!isPayPalSandboxLink(invoice.payerViewUrl())) {
			return new Dropped(reply, "payerViewUrl is not a https://" + PAY_HOST + "/ link");
		}
		if (!agreedTotal.currency().equals(invoice.total().currency())
				|| agreedTotal.amount().compareTo(invoice.total().amount()) != 0) {
			return new Dropped(reply, "the invoice total " + invoice.total().amount().toPlainString() + " "
					+ invoice.total().currency() + " is not the agreed " + agreedTotal.amount().toPlainString() + " "
					+ agreedTotal.currency());
		}
		return new Invoiced(reply, invoice);
	}

	static boolean isPayPalSandboxLink(String url) {
		if (url == null || url.length() > 2000) {
			return false;
		}
		try {
			URI uri = new URI(url);
			return "https".equals(uri.getScheme()) && PAY_HOST.equals(uri.getHost()) && uri.getUserInfo() == null
					&& uri.getPort() == -1;
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	public sealed interface Outcome permits Invoiced, Refused, Dropped {
	}

	public record Invoiced(AgentEnvelope envelope, InvoiceBody invoice) implements Outcome {
	}

	/** The supplier answered {@code reject}; the reason is safe to show. */
	public record Refused(AgentEnvelope envelope, String reason) implements Outcome {
	}

	/** @param envelope the reply if it could at least be read as an envelope, else null */
	public record Dropped(AgentEnvelope envelope, String problem) implements Outcome {
	}

}
