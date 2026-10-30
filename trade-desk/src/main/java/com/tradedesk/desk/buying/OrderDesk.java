package com.tradedesk.desk.buying;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tradedesk.desk.agent.AcceptBody;
import com.tradedesk.desk.agent.AgentClient;
import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.AgentReplies;
import com.tradedesk.desk.agent.ConversationLog;
import com.tradedesk.desk.agent.PeerDirectory;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.events.DeskEvent;
import com.tradedesk.desk.events.DeskEventStream;
import com.tradedesk.desk.need.NeedRepository;
import com.tradedesk.desk.need.NeedRow;
import com.tradedesk.desk.need.QuoteRow;
import com.tradedesk.desk.paypal.PayPalProperties;
import com.tradedesk.desk.policy.PolicyDecision;
import com.tradedesk.desk.policy.PolicyEngine;
import com.tradedesk.desk.policy.PolicyRepository;
import com.tradedesk.desk.policy.ProposedOrder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * From a picked quote to an invoice the café has to pay (S3). The {@link PolicyEngine} is the only gate: an
 * {@code accept} is sent from one place, {@link #send}, which is reached only after the engine said
 * {@code AUTO_ACCEPT}, or said {@code NEEDS_APPROVAL} and the owner tapped Approve and the engine, asked again with the
 * policy and the time of the tap, didn't say {@code BLOCKED}. Nothing reaches the supplier while an approval is pending.
 *
 * <p>The supplier's {@code invoice} reply is checked by {@link InvoiceReplyCheck} (sender, sandbox pay link, agreed
 * total) before it becomes a payable.
 */
@Service
public class OrderDesk {

	/** An accept that got no answer is sent again, with the same message id, this many times in all. */
	static final int ACCEPT_TRIES = 3;

	static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

	private static final Logger log = LoggerFactory.getLogger(OrderDesk.class);

	private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();

	private final NegotiationRepository negotiations;

	private final PayableRepository payables;

	private final NeedRepository needs;

	private final PolicyRepository policies;

	private final PolicyEngine policyEngine;

	private final PeerDirectory peers;

	private final AgentClient agents;

	private final AgentReplies messages;

	private final AgentJson json;

	private final ConversationLog conversations;

	private final DeskEventStream events;

	private final RecommendationView views;

	private final DeskProperties desk;

	private final PayPalProperties payPal;

	private final Clock clock;

	public OrderDesk(NegotiationRepository negotiations, PayableRepository payables, NeedRepository needs,
			PolicyRepository policies, PolicyEngine policyEngine, PeerDirectory peers, AgentClient agents,
			AgentReplies messages, AgentJson json, ConversationLog conversations, DeskEventStream events,
			RecommendationView views, DeskProperties desk, PayPalProperties payPal, Clock clock) {
		this.negotiations = negotiations;
		this.payables = payables;
		this.needs = needs;
		this.policies = policies;
		this.policyEngine = policyEngine;
		this.peers = peers;
		this.agents = agents;
		this.messages = messages;
		this.json = json;
		this.conversations = conversations;
		this.events = events;
		this.views = views;
		this.desk = desk;
		this.payPal = payPal;
		this.clock = clock;
	}

	/**
	 * Asks the policy engine about the need's pick and stores its decision. {@code AUTO_ACCEPT} sends the accept at
	 * once; {@code NEEDS_APPROVAL} emits {@code approval.needed} and sends nothing; {@code BLOCKED} never sends.
	 */
	void decide(NeedRow need, QuoteRow pick) {
		PolicyDecision decision = evaluate(need, pick);
		Approval.Status status = switch (decision.outcome()) {
			case AUTO_ACCEPT -> Approval.Status.AUTO_APPROVED;
			case NEEDS_APPROVAL -> Approval.Status.PENDING;
			case BLOCKED -> Approval.Status.BLOCKED;
		};
		UUID id = UUID.randomUUID();
		boolean stored = negotiations.insertApproval(id, need.id(), pick.id(), pick.total(), decision, status,
				status == Approval.Status.PENDING ? null : clock.instant());
		if (!stored) {
			return;
		}
		log.info("Need {}: policy engine says {} for {} {} from {}", need.id(), decision.outcome(),
				pick.total().amount(), pick.total().currency(), pick.supplier().domain());
		switch (status) {
			case PENDING -> events.publish(new DeskEvent("approval.needed", negotiations.approval(id).orElseThrow()));
			case AUTO_APPROVED -> send(id);
			default -> {
			}
		}
	}

	/**
	 * The owner's tap. Approve asks the policy engine again first, with the current policy and time. Approve on an
	 * approved order that {@code FAILED} sends the same accept again, after the same check.
	 * @throws ApprovalNotFoundException for an unknown approval
	 * @throws NegotiationConflictException if it isn't pending (or a failed order to retry), or the engine now says
	 * {@code BLOCKED}
	 */
	public Approval approve(UUID approvalId, Decision decision) {
		Approval approval = negotiations.approval(approvalId).orElseThrow(ApprovalNotFoundException::new);
		if (decision == Decision.APPROVE && retryable(approval)) {
			return retry(approval);
		}
		if (approval.status() != Approval.Status.PENDING) {
			throw new NegotiationConflictException("This approval was already decided ("
					+ approval.status().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ").");
		}
		if (decision == Decision.REJECT) {
			if (!negotiations.decide(approvalId, Approval.Status.REJECTED, clock.instant())) {
				throw new NegotiationConflictException("This approval was already decided.");
			}
			log.info("Owner rejected approval {}; nothing was sent", approvalId);
			return published(approval.needId(), approvalId);
		}
		NeedRow need = needs.find(approval.needId()).orElseThrow();
		QuoteRow pick = pick(need, approval.quoteId());
		PolicyDecision now = evaluate(need, pick);
		if (now.outcome() == PolicyDecision.Outcome.BLOCKED) {
			negotiations.block(approvalId, now, clock.instant());
			published(approval.needId(), approvalId);
			throw new NegotiationConflictException("This order can't be approved: " + String.join(" ", now.reasons()));
		}
		if (!negotiations.decide(approvalId, Approval.Status.APPROVED, clock.instant())) {
			throw new NegotiationConflictException("This approval was already decided.");
		}
		log.info("Owner approved approval {}", approvalId);
		send(approvalId);
		return negotiations.approval(approvalId).orElseThrow();
	}

	private static boolean retryable(Approval approval) {
		return approval.order() == Approval.Order.FAILED && (approval.status() == Approval.Status.APPROVED
				|| approval.status() == Approval.Status.AUTO_APPROVED);
	}

	/**
	 * Sends a failed accept again, with its stored message id, so a supplier that already invoiced it returns the same
	 * invoice. The policy engine is asked again first; a {@code BLOCKED} answer sends nothing and leaves it failed.
	 */
	private Approval retry(Approval approval) {
		NeedRow need = needs.find(approval.needId()).orElseThrow();
		PolicyDecision now = evaluate(need, pick(need, approval.quoteId()));
		if (now.outcome() == PolicyDecision.Outcome.BLOCKED) {
			throw new NegotiationConflictException("This order can't be sent: " + String.join(" ", now.reasons()));
		}
		if (!negotiations.retry(approval.id())) {
			throw new NegotiationConflictException("This order is already being sent again.");
		}
		log.info("Owner retried the failed order for approval {}", approval.id());
		send(approval.id());
		return negotiations.approval(approval.id()).orElseThrow();
	}

	public List<PayableRow> payables() {
		return payables.findAll();
	}

	/** Accepts that were in flight when the desk stopped are sent again, with the same message id. */
	@EventListener(ApplicationReadyEvent.class)
	void resume() {
		negotiations.sending().forEach(this::send);
	}

	private PolicyDecision evaluate(NeedRow need, QuoteRow pick) {
		ProposedOrder order = new ProposedOrder(pick.supplier().domain(), pick.unitPrice(), pick.quantity(),
				pick.unit(), need.request().quantity(), pick.total(), pick.validUntil(), need.request().maxUnitPrice());
		return policyEngine.evaluate(order, policies.get(), clock.instant());
	}

	/**
	 * The only place an accept leaves this desk. Callers have passed the policy engine (and the owner, when it asked).
	 */
	private void send(UUID approvalId) {
		UUID messageId = negotiations.acceptMessageId(approvalId);
		negotiations.order(approvalId, Approval.Order.SENDING, null);
		Approval approval = negotiations.approval(approvalId).orElseThrow();
		views.publish(approval.needId());
		senders.submit(() -> {
			try {
				deliver(approval, messageId);
			}
			catch (RuntimeException ex) {
				log.warn("Accept for approval {} failed: {}", approvalId, ex.toString());
				fail(approval, "The accept couldn't be sent. Tap Try again to resend it.");
			}
		});
	}

	void deliver(Approval approval, UUID messageId) {
		NeedRepository.QuoteRef ref = needs.quoteRef(approval.quoteId()).orElseThrow();
		PeerDirectory.Peer peer = peers.supplier(ref.supplierDomain()).orElse(null);
		String email = payPal.sandboxBuyerEmail();
		if (ref.quoteMessageId() == null || peer == null) {
			fail(approval, "This quote can't be accepted from this desk any more. Post the need again.");
			return;
		}
		if (email == null || email.isBlank()) {
			fail(approval, "Set PAYPAL_SANDBOX_BUYER_EMAIL for the café so the supplier knows who to invoice.");
			return;
		}
		AgentEnvelope accept = messages.request(messageId, AgentEnvelope.Type.ACCEPT, ref.supplierDomain(),
				approval.needId(), new AcceptBody(ref.quoteMessageId(), new AcceptBody.BillTo(desk.name(), email)));
		String raw = json.write(accept);
		conversations.sent(accept, raw, null);

		AgentClient.Reply reply = null;
		for (int attempt = 1; attempt <= ACCEPT_TRIES; attempt++) {
			reply = agents.sendSlow(peer, raw);
			// Only a missing answer is worth another try; the same message id gets the stored reply if there was one.
			if (reply.ok() || reply.status() >= 400 && reply.status() < 500) {
				break;
			}
			pause();
		}
		if (!reply.ok()) {
			String problem = reply.failure() != null ? "unreachable (" + reply.failure() + ")"
					: "answered HTTP " + reply.status();
			conversations.refused(null, approval.needId(), reply.body() == null ? "" : reply.body(), problem);
			fail(approval, "The supplier didn't answer the accept. Tap Try again to resend it.");
			return;
		}
		switch (InvoiceReplyCheck.check(json, reply.body(), accept, ref.supplierDomain(), approval.total())) {
			case InvoiceReplyCheck.Invoiced invoiced -> {
				conversations.received(invoiced.envelope(), reply.body());
				NeedRow need = needs.find(approval.needId()).orElseThrow();
				PayableRow payable = payables.insert(approval.id(), approval.needId(), approval.supplier(),
						description(need, pick(need, approval.quoteId())), invoiced.invoice().paypalInvoiceId(),
						invoiced.invoice().payerViewUrl(), invoiced.invoice().total(), invoiced.invoice().status(),
						invoiced.envelope().sentAt() == null ? clock.instant() : invoiced.envelope().sentAt());
				negotiations.order(approval.id(), Approval.Order.INVOICED, null);
				log.info("Need {}: {} sent invoice {}", approval.needId(), ref.supplierDomain(),
						payable.paypalInvoiceId());
				events.publish(new DeskEvent("payable.updated", payable));
				views.publish(approval.needId());
			}
			case InvoiceReplyCheck.Refused refused -> {
				conversations.received(refused.envelope(), reply.body());
				log.info("Need {}: {} refused the accept: {}", approval.needId(), ref.supplierDomain(),
						refused.reason());
				negotiations.order(approval.id(), Approval.Order.REFUSED, refused.reason().strip());
				views.publish(approval.needId());
			}
			case InvoiceReplyCheck.Dropped dropped -> {
				conversations.refused(dropped.envelope(), approval.needId(), reply.body(), dropped.problem());
				log.warn("Need {}: refused the invoice from {}: {}", approval.needId(), ref.supplierDomain(),
						dropped.problem());
				fail(approval, "The supplier's invoice didn't match the accepted quote, so it wasn't recorded.");
			}
		}
	}

	private void fail(Approval approval, String detail) {
		negotiations.order(approval.id(), Approval.Order.FAILED, detail);
		views.publish(approval.needId());
	}

	private Approval published(UUID needId, UUID approvalId) {
		views.publish(needId);
		return negotiations.approval(approvalId).orElseThrow();
	}

	private QuoteRow pick(NeedRow need, UUID quoteId) {
		return needs.quotes(need.id(), need.request().quantity())
			.stream()
			.filter(q -> q.id().equals(quoteId))
			.findFirst()
			.orElseThrow();
	}

	/** Written from the café's own need, never from the supplier's text. */
	static String description(NeedRow need, QuoteRow pick) {
		return pick.quantity().stripTrailingZeros().toPlainString() + " " + pick.unit().wireName() + " "
				+ need.request().product()
				+ (need.request().origin() == null ? "" : " from " + need.request().origin());
	}

	private static void pause() {
		try {
			Thread.sleep(RETRY_PAUSE);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@PreDestroy
	void shutdown() {
		senders.shutdownNow();
	}

	/** The owner's answer to {@code POST /api/approvals/{id}}. */
	public enum Decision {

		APPROVE, REJECT

	}

}
