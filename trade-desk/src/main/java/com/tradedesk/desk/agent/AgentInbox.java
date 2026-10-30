package com.tradedesk.desk.agent;

import java.util.regex.Pattern;

import com.tradedesk.desk.buying.InvoiceStatusReceiver;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.supplier.AcceptHandler;
import com.tradedesk.desk.supplier.CounterHandler;
import com.tradedesk.desk.supplier.RfqHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Every message another agent sends this desk comes through here. The raw text is untrusted: it is stored as
 * received, parsed strictly, checked (addressed to this desk, a request type rather than a reply), and only then handed
 * to the handler for its type. A repeated {@code messageId} gets the stored reply, so a retry never produces a second
 * quote or invoice.
 */
@Service
public class AgentInbox {

	/** The envelope schema's domain pattern (contracts/envelope.schema.json). */
	static final Pattern DOMAIN = Pattern
		.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

	private static final Logger log = LoggerFactory.getLogger(AgentInbox.class);

	private final AgentJson json;

	private final ConversationLog conversations;

	private final RfqHandler rfqs;

	private final CounterHandler counters;

	private final AcceptHandler accepts;

	private final InvoiceStatusReceiver statuses;

	private final DeskProperties desk;

	public AgentInbox(AgentJson json, ConversationLog conversations, RfqHandler rfqs, CounterHandler counters,
			AcceptHandler accepts, InvoiceStatusReceiver statuses, DeskProperties desk) {
		this.json = json;
		this.conversations = conversations;
		this.rfqs = rfqs;
		this.counters = counters;
		this.accepts = accepts;
		this.statuses = statuses;
		this.desk = desk;
	}

	/**
	 * @return the reply envelope as JSON text
	 * @throws InvalidAgentMessageException for anything that isn't a well-formed message for this desk
	 */
	public String receive(String raw) {
		AgentEnvelope envelope = json.envelope(raw).orElse(null);
		String problem = problem(envelope);
		if (problem != null) {
			conversations.refused(envelope, envelope == null ? null : envelope.conversationId(), raw, problem);
			log.warn("Refused an agent message: {}", problem);
			throw new InvalidAgentMessageException(problem);
		}
		var stored = conversations.replyTo(envelope.messageId());
		if (stored.isPresent()) {
			return stored.get();
		}
		conversations.received(envelope, raw);
		AgentEnvelope reply = switch (envelope.type()) {
			case RFQ -> rfqs.answer(envelope);
			case COUNTER -> counters.answer(envelope);
			case ACCEPT -> accepts.answer(envelope);
			case INVOICE_STATUS -> statuses.answer(envelope);
			default -> throw new IllegalStateException("problem() lets only requests through");
		};
		String replyRaw = json.write(reply);
		try {
			conversations.sent(reply, replyRaw, envelope.messageId());
		}
		catch (DuplicateKeyException ex) {
			// The same message arrived twice at once and the other request answered first: send that answer.
			return conversations.replyTo(envelope.messageId()).orElseThrow();
		}
		return replyRaw;
	}

	private String problem(AgentEnvelope envelope) {
		if (envelope == null) {
			return "The body is not a valid agent envelope.";
		}
		if (envelope.messageId() == null || envelope.conversationId() == null || envelope.sentAt() == null
				|| envelope.type() == null || envelope.body() == null) {
			return "The envelope is missing a required field.";
		}
		if (envelope.from() == null || !DOMAIN.matcher(envelope.from()).matches()) {
			return "The envelope's from is not a domain.";
		}
		if (!desk.domain().equals(envelope.to())) {
			return "The envelope is addressed to another desk.";
		}
		return switch (envelope.type()) {
			case RFQ, COUNTER, ACCEPT, INVOICE_STATUS -> null;
			case QUOTE, REJECT, INVOICE -> "A " + envelope.type().wireName() + " is a reply, not a request.";
		};
	}

}
