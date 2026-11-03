package com.tradedesk.desk.need;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tradedesk.desk.agent.AgentClient;
import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.ConversationLog;
import com.tradedesk.desk.agent.PeerDirectory;
import com.tradedesk.desk.agent.RfqBody;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.events.DeskEvent;
import com.tradedesk.desk.events.DeskEventStream;
import com.tradedesk.desk.policy.PolicyDecision;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The buyer side of S2: stores a confirmed need, sends an RFQ to every supplier in parallel, checks each reply with
 * {@link QuoteValidator}, and streams the ranked quotes to the UI as they arrive. Idempotent per
 * {@code Idempotency-Key}, so a double click never sends a second round of RFQs.
 *
 * <p>The RFQ body is an {@link RfqBody}, which has no field for the café's budget, stock or priority: none of them
 * can reach a supplier.
 */
@Service
public class NeedService {

	private static final Logger log = LoggerFactory.getLogger(NeedService.class);

	/** One virtual thread per supplier call; they mostly wait on the network. */
	private final ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor();

	private final NeedRepository needs;

	private final PeerDirectory peers;

	private final AgentClient agents;

	private final AgentJson json;

	private final ConversationLog conversations;

	private final DeskEventStream events;

	private final DeskProperties desk;

	private final Clock clock;

	private final ApplicationEventPublisher publisher;

	private final QuoteScreen screen;

	public NeedService(NeedRepository needs, PeerDirectory peers, AgentClient agents, AgentJson json,
			ConversationLog conversations, DeskEventStream events, DeskProperties desk, Clock clock,
			ApplicationEventPublisher publisher, QuoteScreen screen) {
		this.screen = screen;
		this.needs = needs;
		this.peers = peers;
		this.agents = agents;
		this.json = json;
		this.conversations = conversations;
		this.events = events;
		this.desk = desk;
		this.clock = clock;
		this.publisher = publisher;
	}

	/**
	 * Checks and stores the need, then starts the fan-out in the background.
	 * @throws InvalidNeedException if the request breaks a rule (nothing is stored or sent)
	 * @throws NeedKeyConflictException if the key was used for a different request
	 */
	public Result post(UUID idempotencyKey, PurchaseRequest request) {
		PurchaseRequest checked = PurchaseRequestRules.check(request, today());
		String hash = hash(checked);
		List<PeerDirectory.Peer> suppliers = peers.suppliers();
		UUID id = UUID.randomUUID();
		boolean created = needs.insert(id, idempotencyKey, hash, checked,
				suppliers.isEmpty() ? NeedRow.Status.COMPLETE : NeedRow.Status.COLLECTING, suppliers.size());
		NeedRepository.Stored stored = needs.findByKey(idempotencyKey).orElseThrow();
		if (!stored.requestHash().equals(hash)) {
			throw new NeedKeyConflictException();
		}
		NeedRow row = needs.find(stored.id()).orElseThrow();
		if (created) {
			log.info("Need {} posted; sending RFQs to {} suppliers", id, suppliers.size());
			events.publish(new DeskEvent("need.updated", row));
			List<CompletableFuture<Void>> calls = suppliers.stream()
				.map(peer -> CompletableFuture.runAsync(() -> askSafely(row, peer), fanOut))
				.toList();
			CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).whenComplete((done, error) -> finish(id));
		}
		return new Result(row, created);
	}

	public List<NeedRow> needs() {
		return needs.findAll();
	}

	/** The need's quotes, ranked for its priority. */
	public List<QuoteRow> quotes(UUID needId) {
		NeedRow need = needs.find(needId).orElseThrow(NeedNotFoundException::new);
		return QuoteRanking.rank(needs.quotes(needId, need.request().quantity()), need.request().priority());
	}

	private void askSafely(NeedRow need, PeerDirectory.Peer peer) {
		try {
			ask(need, peer);
		}
		catch (RuntimeException ex) {
			log.warn("Asking {} for need {} failed: {}", peer.domain(), need.id(), ex.toString());
		}
	}

	/** One supplier: send the RFQ, store both envelopes, keep the quote only if it passes every check. */
	void ask(NeedRow need, PeerDirectory.Peer peer) {
		PurchaseRequest request = need.request();
		RfqBody body = new RfqBody(request.product(), request.origin(), request.quantity(), request.unit(),
				request.deliverBy());
		AgentEnvelope rfq = new AgentEnvelope(UUID.randomUUID(), AgentEnvelope.Type.RFQ, desk.domain(), peer.domain(),
				need.id(), clock.instant(), json.tree(body));
		String raw = json.write(rfq);
		conversations.sent(rfq, raw, null);

		AgentClient.Reply reply = agents.send(peer, raw);
		if (!reply.ok()) {
			String problem = reply.failure() != null ? "unreachable (" + reply.failure() + ")"
					: "answered HTTP " + reply.status();
			conversations.refused(null, need.id(), reply.body() == null ? "" : reply.body(), problem);
			log.warn("No quote from {} for need {}: {}", peer.domain(), need.id(), problem);
			return;
		}
		Instant now = clock.instant();
		QuoteValidator.Outcome outcome = QuoteValidator.check(json, reply.body(), rfq, peer.domain(), request, now,
				today());
		switch (outcome) {
			case QuoteValidator.Accepted accepted -> {
				conversations.received(accepted.envelope(), reply.body());
				String note = accepted.quote().note().replaceAll("\\p{Cntrl}", " ").strip();
				// The policy engine looks at the quote before the grid does (S8): a blocked quote is shown as such.
				PolicyDecision policy = screen.screen(request, peer.domain(), accepted.quote());
				if (needs.insertQuote(need.id(), peer.domain(), accepted.envelope().messageId(), accepted.quote(), note,
						reply.body(), policy)) {
					log.info("Quote from {} for need {}: policy engine says {}", peer.domain(), need.id(),
							policy.outcome());
					publishQuotes(need.id());
				}
			}
			case QuoteValidator.Rejected rejected -> {
				conversations.received(rejected.envelope(), reply.body());
				log.info("{} declined need {}: {}", peer.domain(), need.id(), rejected.reject().reason());
			}
			case QuoteValidator.Dropped dropped -> {
				conversations.refused(dropped.envelope(), need.id(), reply.body(), dropped.problem());
				log.warn("Dropped a malformed quote from {} for need {}: {}", peer.domain(), need.id(),
						dropped.problem());
				needs.insertRefusal(need.id(), peer.domain(), shown(dropped.problem()));
			}
		}
	}

	/** The refusal as the owner sees it: one clean line, at most {@link QuoteValidator#MAX_PROBLEM} characters. */
	private static String shown(String problem) {
		String line = problem.replaceAll("\\p{Cntrl}", " ").strip();
		return line.length() > QuoteValidator.MAX_PROBLEM ? line.substring(0, QuoteValidator.MAX_PROBLEM - 1) + "…"
				: line;
	}

	/**
	 * Asks the policy engine about every quote of the need again, with the current policy and time, and publishes the
	 * list if any verdict changed. The owner may have edited the limits since a quote arrived, and a counter changes a
	 * quote's price.
	 */
	public void screenQuotes(UUID needId) {
		NeedRow need = needs.find(needId).orElseThrow(NeedNotFoundException::new);
		boolean changed = false;
		for (QuoteRow quote : needs.quotes(needId, need.request().quantity())) {
			changed |= needs.updatePolicy(quote.id(), screen.screen(need.request(), quote));
		}
		if (changed) {
			publishQuotes(needId);
		}
	}

	/**
	 * Sends the need's whole ranked list as {@code quote.received}. Synchronized so two changes arriving together
	 * can't publish an older list after a newer one; S3 calls it as counters go out and come back.
	 */
	public synchronized void publishQuotes(UUID needId) {
		events.publish(new DeskEvent("quote.received", Map.of("needId", needId, "quotes", quotes(needId))));
	}

	private void finish(UUID needId) {
		try {
			needs.complete(needId);
			needs.find(needId).ifPresent(row -> events.publish(new DeskEvent("need.updated", row)));
			// S3's buyer agent starts its counter round from here.
			publisher.publishEvent(new NeedCompleted(needId));
		}
		catch (RuntimeException ex) {
			log.warn("Could not mark need {} complete: {}", needId, ex.getClass().getSimpleName());
		}
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(desk.timeZone()));
	}

	private String hash(PurchaseRequest request) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(json.write(request).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@PreDestroy
	void shutdown() {
		fanOut.shutdownNow();
	}

	/**
	 * @param need the need as stored after this call
	 * @param created true if this call created it, false for a retry
	 */
	public record Result(NeedRow need, boolean created) {
	}

}
