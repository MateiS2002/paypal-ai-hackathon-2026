package com.tradedesk.desk.supplier;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.tradedesk.desk.agent.AgentClient;
import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.AgentReplies;
import com.tradedesk.desk.agent.ConversationLog;
import com.tradedesk.desk.agent.InvoiceStatusBody;
import com.tradedesk.desk.agent.PeerDirectory;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.invoice.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tells buyers when a negotiation invoice changes status (S3): the S1 poller records PayPal's status, and this sends
 * {@code invoice.status} to the invoice's buyer until the buyer acknowledges it. Pending updates are read from the
 * database every round, so a buyer that was down hears the latest status once it is back.
 *
 * <p>Only buyers in {@code DESK_BUYERS} are ever called; an address is never taken from a message.
 */
@Component
public class BuyerNotifier {

	private static final Logger log = LoggerFactory.getLogger(BuyerNotifier.class);

	/** Invoices already warned about, so a buyer that stays down doesn't fill the log every few seconds. */
	private final Set<UUID> warned = ConcurrentHashMap.newKeySet();

	private final InvoiceRepository invoices;

	private final PeerDirectory peers;

	private final AgentClient agents;

	private final AgentReplies messages;

	private final AgentJson json;

	private final ConversationLog conversations;

	public BuyerNotifier(InvoiceRepository invoices, PeerDirectory peers, AgentClient agents, AgentReplies messages,
			AgentJson json, ConversationLog conversations) {
		this.invoices = invoices;
		this.peers = peers;
		this.agents = agents;
		this.messages = messages;
		this.json = json;
		this.conversations = conversations;
	}

	@Scheduled(fixedDelayString = "${desk.buyer-notify-interval:PT3S}",
			initialDelayString = "${desk.buyer-notify-interval:PT3S}")
	public void notifyBuyers() {
		if (!peers.hasBuyers()) {
			return;
		}
		for (InvoiceRepository.BuyerUpdate update : invoices.findBuyerUpdates()) {
			try {
				notify(update);
			}
			catch (RuntimeException ex) {
				log.warn("Telling the buyer about invoice {} failed: {}", update.id(), ex.toString());
			}
		}
	}

	void notify(InvoiceRepository.BuyerUpdate update) {
		String buyerDomain = update.negotiation().buyerDomain();
		PeerDirectory.Peer buyer = peers.buyer(buyerDomain).orElse(null);
		if (buyer == null) {
			// Nobody to tell: stop trying for this status rather than retrying forever.
			log.warn("Invoice {} belongs to buyer {}, which is not in DESK_BUYERS; not sending invoice.status",
					update.id(), buyerDomain);
			invoices.markBuyerNotified(update.id(), update.status());
			return;
		}
		AgentEnvelope message = messages.request(UUID.randomUUID(), AgentEnvelope.Type.INVOICE_STATUS, buyerDomain,
				update.negotiation().conversationId(),
				new InvoiceStatusBody(update.payPalInvoiceId(), update.status(), update.updatedAt()));
		String raw = json.write(message);
		conversations.sent(message, raw, null);

		AgentClient.Reply reply = agents.send(buyer, raw);
		AgentEnvelope answer = reply.ok() ? json.envelope(reply.body()).orElse(null) : null;
		boolean valid = answer != null && buyerDomain.equals(answer.from()) && message.from().equals(answer.to())
				&& message.conversationId().equals(answer.conversationId())
				&& (answer.type() == AgentEnvelope.Type.INVOICE_STATUS || answer.type() == AgentEnvelope.Type.REJECT);
		if (!valid) {
			String problem = !reply.ok() ? (reply.failure() != null ? "unreachable (" + reply.failure() + ")"
					: "answered HTTP " + reply.status()) : "not an invoice.status or reject reply";
			conversations.refused(answer, message.conversationId(), reply.body() == null ? "" : reply.body(), problem);
			if (warned.add(update.id())) {
				log.warn("Buyer {} didn't take the {} status of invoice {}: {}; retrying", buyerDomain,
						update.status(), update.id(), problem);
			}
			return;
		}
		conversations.received(answer, reply.body());
		if (answer.type() == AgentEnvelope.Type.REJECT) {
			log.warn("Buyer {} ignored the {} status of invoice {}", buyerDomain, update.status(), update.id());
		}
		invoices.markBuyerNotified(update.id(), update.status());
		warned.remove(update.id());
	}

}
