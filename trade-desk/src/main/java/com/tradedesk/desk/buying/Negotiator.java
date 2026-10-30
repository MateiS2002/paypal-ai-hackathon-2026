package com.tradedesk.desk.buying;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tradedesk.desk.agent.AgentClient;
import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.AgentReplies;
import com.tradedesk.desk.agent.ConversationLog;
import com.tradedesk.desk.agent.CounterBody;
import com.tradedesk.desk.agent.PeerDirectory;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.need.NeedCompleted;
import com.tradedesk.desk.need.NeedNotFoundException;
import com.tradedesk.desk.need.NeedRepository;
import com.tradedesk.desk.need.NeedRow;
import com.tradedesk.desk.need.NeedService;
import com.tradedesk.desk.need.QuoteRanking;
import com.tradedesk.desk.need.QuoteRow;
import com.tradedesk.desk.need.QuoteValidator;
import com.tradedesk.desk.policy.PolicyRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The buyer agent's negotiation for one need (S3): once every supplier has answered the RFQ, one counter to each of
 * the top {@link #COUNTERED} quotes, then a pick. Automatic by default and shown as it happens: each counter going
 * out and coming back is a {@code quote.received} event.
 *
 * <p>The LLM proposes counter targets ({@link CounterPlanner}, clamped in Java) and writes the reason
 * ({@link RecommendationWriter}, discarded if it argues for anyone else). Java picks the winner with S2's
 * {@link QuoteRanking} among quotes still open, and {@link OrderDesk} puts it to the policy engine.
 */
@Service
public class Negotiator {

	/** How many of the best quotes get a counter. */
	static final int COUNTERED = 3;

	private static final Logger log = LoggerFactory.getLogger(Negotiator.class);

	/** One virtual thread per round and per counter; they mostly wait on the network. */
	private final ExecutorService rounds = Executors.newVirtualThreadPerTaskExecutor();

	private final NeedRepository needs;

	private final NeedService needService;

	private final NegotiationRepository negotiations;

	private final PolicyRepository policies;

	private final CounterPlanner planner;

	private final RecommendationWriter writer;

	private final OrderDesk orders;

	private final RecommendationView views;

	private final PeerDirectory peers;

	private final AgentClient agents;

	private final AgentReplies messages;

	private final AgentJson json;

	private final ConversationLog conversations;

	private final Clock clock;

	private final boolean autoCounter;

	public Negotiator(NeedRepository needs, NeedService needService, NegotiationRepository negotiations,
			PolicyRepository policies, CounterPlanner planner, RecommendationWriter writer, OrderDesk orders,
			RecommendationView views, PeerDirectory peers, AgentClient agents, AgentReplies messages, AgentJson json,
			ConversationLog conversations, Clock clock, @Value("${desk.auto-counter:true}") boolean autoCounter) {
		this.needs = needs;
		this.needService = needService;
		this.negotiations = negotiations;
		this.policies = policies;
		this.planner = planner;
		this.writer = writer;
		this.orders = orders;
		this.views = views;
		this.peers = peers;
		this.agents = agents;
		this.messages = messages;
		this.json = json;
		this.conversations = conversations;
		this.clock = clock;
		this.autoCounter = autoCounter;
	}

	/** Counters by itself once every supplier has answered (the review-sensitive default: automatic). */
	@EventListener
	void onNeedCompleted(NeedCompleted completed) {
		if (autoCounter) {
			try {
				start(completed.needId());
			}
			catch (RuntimeException ex) {
				log.warn("Could not start the counter round for need {}: {}", completed.needId(), ex.toString());
			}
		}
	}

	/** Rounds cut short by a restart are finished with what arrived; unanswered counters count as no answer. */
	@EventListener(ApplicationReadyEvent.class)
	void resume() {
		negotiations.countering().forEach(needId -> rounds.submit(() -> finishSafely(needId)));
	}

	/**
	 * Starts the need's one counter round in the background.
	 * @return true if this call started it, false if it had already started
	 * @throws NeedNotFoundException for an unknown need
	 * @throws NegotiationConflictException while the need is still collecting quotes, or if it has none
	 */
	public boolean start(UUID needId) {
		NeedRow need = needs.find(needId).orElseThrow(NeedNotFoundException::new);
		if (negotiations.find(needId).isPresent()) {
			return false;
		}
		if (need.status() == NeedRow.Status.COLLECTING) {
			throw new NegotiationConflictException("The suppliers are still quoting. Counter once every one has "
					+ "answered.");
		}
		if (need.quotesReceived() == 0) {
			throw new NegotiationConflictException("No supplier quoted, so there is nothing to counter.");
		}
		if (open(needService.quotes(needId), clock.instant()).isEmpty()) {
			throw new NegotiationConflictException("Every quote has expired, so there is nothing to counter. Post the "
					+ "need again for fresh quotes.");
		}
		if (!negotiations.start(needId)) {
			return false;
		}
		log.info("Need {}: counter round started", needId);
		views.publish(needId);
		rounds.submit(() -> {
			try {
				counterRound(need);
			}
			catch (RuntimeException ex) {
				log.warn("Counter round for need {} failed: {}", needId, ex.toString());
			}
			finishSafely(needId);
		});
		return true;
	}

	private void counterRound(NeedRow need) {
		Instant now = clock.instant();
		List<QuoteRow> top = open(needService.quotes(need.id()), now).stream().limit(COUNTERED).toList();
		Map<UUID, BigDecimal> targets = planner.plan(need.request(), top, policies.get());
		List<CompletableFuture<Void>> counters = top.stream()
			.filter(quote -> targets.containsKey(quote.id()))
			.map(quote -> CompletableFuture.runAsync(() -> counterSafely(need, quote, targets.get(quote.id())),
					rounds))
			.toList();
		CompletableFuture.allOf(counters.toArray(CompletableFuture[]::new)).join();
	}

	private void counterSafely(NeedRow need, QuoteRow quote, BigDecimal target) {
		try {
			counter(need, quote, target);
		}
		catch (RuntimeException ex) {
			log.warn("Counter to {} for need {} failed: {}", quote.supplier().domain(), need.id(), ex.toString());
			needs.counterAnswered(quote.id(), QuoteRow.Counter.Outcome.NO_ANSWER);
			needService.publishQuotes(need.id());
		}
	}

	/** One supplier: send the counter, store both envelopes, keep the revised quote only if it passes every check. */
	void counter(NeedRow need, QuoteRow quote, BigDecimal target) {
		NeedRepository.QuoteRef ref = needs.quoteRef(quote.id()).orElseThrow();
		PeerDirectory.Peer peer = peers.supplier(ref.supplierDomain()).orElse(null);
		if (ref.quoteMessageId() == null || ref.rfqMessageId() == null || peer == null
				|| !needs.startCounter(quote.id(), target)) {
			return;
		}
		needService.publishQuotes(need.id());
		Money targetPrice = new Money(target, quote.unitPrice().currency());
		AgentEnvelope counter = messages.request(UUID.randomUUID(), AgentEnvelope.Type.COUNTER, ref.supplierDomain(),
				need.id(), new CounterBody(ref.quoteMessageId(), targetPrice));
		String raw = json.write(counter);
		conversations.sent(counter, raw, null);

		AgentClient.Reply reply = agents.send(peer, raw);
		QuoteRow.Counter.Outcome outcome;
		if (!reply.ok()) {
			String problem = reply.failure() != null ? "unreachable (" + reply.failure() + ")"
					: "answered HTTP " + reply.status();
			conversations.refused(null, need.id(), reply.body() == null ? "" : reply.body(), problem);
			outcome = QuoteRow.Counter.Outcome.NO_ANSWER;
			needs.counterAnswered(quote.id(), outcome);
		}
		else {
			switch (QuoteValidator.checkRevision(json, reply.body(), counter, quote, ref, clock.instant())) {
				case QuoteValidator.Accepted revised -> {
					conversations.received(revised.envelope(), reply.body());
					BigDecimal price = revised.quote().unitPrice().amount();
					outcome = price.compareTo(target) <= 0 ? QuoteRow.Counter.Outcome.MET
							: price.compareTo(quote.unitPrice().amount()) < 0 ? QuoteRow.Counter.Outcome.PARTLY_MET
									: QuoteRow.Counter.Outcome.HELD;
					String note = revised.quote().note().replaceAll("\\p{Cntrl}", " ").strip();
					needs.revise(quote.id(), revised.envelope().messageId(), revised.quote(), note, outcome);
				}
				case QuoteValidator.Rejected rejected -> {
					conversations.received(rejected.envelope(), reply.body());
					outcome = QuoteRow.Counter.Outcome.HELD;
					needs.counterAnswered(quote.id(), outcome);
				}
				case QuoteValidator.Dropped dropped -> {
					conversations.refused(dropped.envelope(), need.id(), reply.body(), dropped.problem());
					log.warn("Dropped a malformed counter answer from {} for need {}: {}", ref.supplierDomain(),
							need.id(), dropped.problem());
					outcome = QuoteRow.Counter.Outcome.NO_ANSWER;
					needs.counterAnswered(quote.id(), outcome);
				}
			}
		}
		log.info("Need {}: counter to {} at {} {}", need.id(), ref.supplierDomain(), target, outcome);
		needService.publishQuotes(need.id());
	}

	private void finishSafely(UUID needId) {
		try {
			finish(needId);
		}
		catch (RuntimeException ex) {
			log.warn("Could not finish the negotiation for need {}: {}", needId, ex.toString());
		}
	}

	/**
	 * Java's pick: the quote S2's ranking puts first among those still open, leaving out any whose counter got no answer. The reason is
	 * written for it, and the policy engine decides what happens next.
	 */
	void finish(UUID needId) {
		NeedRow need = needs.find(needId).orElseThrow();
		boolean countering = negotiations.find(needId)
			.map(n -> n.stage() == NegotiationRepository.Negotiation.Stage.COUNTERING)
			.orElse(false);
		if (!countering) {
			return;
		}
		List<QuoteRow> unanswered = needService.quotes(needId)
			.stream()
			.filter(q -> q.counter() != null && q.counter().outcome() == QuoteRow.Counter.Outcome.PENDING)
			.toList();
		if (!unanswered.isEmpty()) {
			unanswered.forEach(q -> needs.counterAnswered(q.id(), QuoteRow.Counter.Outcome.NO_ANSWER));
			needService.publishQuotes(needId);
		}
		// A quote whose counter got no answer (or a dropped one) may have been replaced by the supplier: never pick it.
		List<QuoteRow> pickable = open(needService.quotes(needId), clock.instant()).stream()
			.filter(q -> q.counter() == null || q.counter().outcome() != QuoteRow.Counter.Outcome.NO_ANSWER)
			.toList();
		List<QuoteRow> ranked = QuoteRanking.rank(pickable, need.request().priority());
		if (ranked.isEmpty()) {
			negotiations.noOffer(needId);
			log.info("Need {}: no open quote to pick", needId);
			views.publish(needId);
			return;
		}
		// A round cut short after the decision was stored keeps that decision's quote.
		UUID decided = negotiations.approvalForNeed(needId).map(Approval::quoteId).orElse(null);
		QuoteRow pick = ranked.stream().filter(q -> q.id().equals(decided)).findFirst().orElse(ranked.getFirst());
		RecommendationWriter.Reason reason = writer.write(need.request(), ranked);
		// The policy engine's decision is stored before the stage turns READY, so no read sees READY without its
		// approval. If the desk stops in between, the round is still COUNTERING and resume() finishes it; decide() then
		// keeps the approval it already stored.
		orders.decide(need, pick);
		negotiations.ready(needId, pick.id(), reason);
		log.info("Need {}: Java picked {} ({} reason)", needId, pick.supplier().domain(), reason.source());
		views.publish(needId);
	}

	/** Quotes still within their {@code validUntil}, in their current order. */
	static List<QuoteRow> open(List<QuoteRow> quotes, Instant now) {
		return quotes.stream().filter(q -> q.validUntil().isAfter(now)).toList();
	}

	@PreDestroy
	void shutdown() {
		rounds.shutdownNow();
	}

}
