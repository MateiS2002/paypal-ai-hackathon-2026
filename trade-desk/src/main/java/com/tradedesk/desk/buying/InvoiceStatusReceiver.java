package com.tradedesk.desk.buying;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.AgentReplies;
import com.tradedesk.desk.agent.InvoiceStatusBody;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.events.DeskEvent;
import com.tradedesk.desk.events.DeskEventStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * The buyer side of {@code invoice.status} (S3): a supplier reports a new PayPal status for an invoice it sent this
 * desk. Only a status for a payable this desk holds, from the desk that is that payable's supplier, and newer than the
 * one held, changes anything; the rest is answered with {@code reject} and ignored.
 *
 * <p>TODO S5: {@code from} is only claimed until S5. Require a registry token with scope {@code invoice:notify} and
 * aud = this desk, and a signed envelope whose {@code from} matches it, before trusting the supplier check below.
 */
@Component
public class InvoiceStatusReceiver {

	private static final Logger log = LoggerFactory.getLogger(InvoiceStatusReceiver.class);

	private final PayableRepository payables;

	private final AgentReplies replies;

	private final AgentJson json;

	private final DeskEventStream events;

	public InvoiceStatusReceiver(PayableRepository payables, AgentReplies replies, AgentJson json,
			DeskEventStream events) {
		this.payables = payables;
		this.replies = replies;
		this.json = json;
		this.events = events;
	}

	public AgentEnvelope answer(AgentEnvelope message) {
		InvoiceStatusBody body = json.body(message.body(), InvoiceStatusBody.class).orElse(null);
		if (body == null || body.paypalInvoiceId() == null || body.status() == null || body.updatedAt() == null
				|| !InvoiceReplyCheck.PAYPAL_INVOICE_ID.matcher(body.paypalInvoiceId()).matches()) {
			return replies.reject(message, "The invoice.status body doesn't match the invoice.status schema.");
		}
		PayableRepository.Stored payable = payables.findByPayPalId(body.paypalInvoiceId())
			.filter(p -> p.row().supplier().domain().equals(message.from()))
			.orElse(null);
		if (payable == null) {
			// The same answer for an unknown id and for someone else's invoice, so neither can be probed.
			log.warn("Ignored an invoice.status from {} for an invoice it didn't send this desk", message.from());
			return replies.reject(message, "We hold no invoice " + body.paypalInvoiceId() + " from you.");
		}
		if (payables.updateStatus(payable.row().id(), body.status(), body.updatedAt())) {
			PayableRow row = payables.find(payable.row().id()).orElseThrow();
			log.info("Payable {} is now {} (was {})", row.id(), row.status(), payable.row().status());
			events.publish(new DeskEvent("payable.updated", row));
		}
		PayableRepository.Stored now = payables.findByPayPalId(body.paypalInvoiceId()).orElseThrow();
		return replies.reply(message, AgentEnvelope.Type.INVOICE_STATUS,
				new InvoiceStatusBody(body.paypalInvoiceId(), now.row().status(), now.statusAt()));
	}

}
