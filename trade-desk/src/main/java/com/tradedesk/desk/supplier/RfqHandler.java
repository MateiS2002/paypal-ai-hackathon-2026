package com.tradedesk.desk.supplier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.agent.RejectBody;
import com.tradedesk.desk.agent.RfqBody;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * The seller side of an RFQ: reads the body, lets {@link QuotePricer} decide every number, asks
 * {@link QuoteNoteWriter} for the note, and builds the {@code quote} or {@code reject} reply. SIMULATED personas wait a
 * moment first so the café's grid fills one quote at a time.
 */
@Component
public class RfqHandler {

	/** How long a quote stays valid. S3 refuses to accept it after this. */
	static final Duration QUOTE_VALID_FOR = Duration.ofHours(24);

	/** The café waits 8 s for a reply; the slowest persona's delay plus this stays well inside it. */
	static final Duration NOTE_TIMEOUT = Duration.ofMillis(3500);

	private static final Logger log = LoggerFactory.getLogger(RfqHandler.class);

	private final ExecutorService noteWriters = Executors.newVirtualThreadPerTaskExecutor();

	private final CatalogueRepository catalogue;

	private final QuoteNoteWriter notes;

	private final AgentJson json;

	private final DeskProperties desk;

	private final Clock clock;

	public RfqHandler(CatalogueRepository catalogue, QuoteNoteWriter notes, AgentJson json, DeskProperties desk,
			Clock clock) {
		this.catalogue = catalogue;
		this.notes = notes;
		this.json = json;
		this.desk = desk;
		this.clock = clock;
	}

	public AgentEnvelope answer(AgentEnvelope rfq) {
		Persona persona = Persona.fromWire(desk.persona());
		if (persona == null) {
			return reject(rfq, "This desk doesn't sell anything.");
		}
		RfqBody body = json.body(rfq.body(), RfqBody.class).orElse(null);
		if (body == null) {
			return reject(rfq, "The RFQ body doesn't match the rfq schema.");
		}
		long replyAt = System.nanoTime() + delay(persona).toNanos();
		LocalDate today = LocalDate.now(clock.withZone(desk.timeZone()));
		QuotePricer.Decision decision = QuotePricer.price(body, catalogue.findAll(), today);
		if (decision instanceof QuotePricer.Refusal refusal) {
			sleepUntil(replyAt);
			log.info("Rejected RFQ {} from {}: {}", rfq.messageId(), rfq.from(), refusal.reason());
			return reject(rfq, refusal.reason());
		}
		QuotePricer.Offer offer = (QuotePricer.Offer) decision;
		// The note is written during the persona's delay, not after it, and never holds the quote up for long:
		// past NOTE_TIMEOUT the template note goes out instead.
		CompletableFuture<String> note = CompletableFuture.supplyAsync(() -> notes.write(offer, persona), noteWriters)
			.completeOnTimeout(QuoteNoteWriter.fallback(offer), NOTE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
			.exceptionally(ex -> QuoteNoteWriter.fallback(offer));
		sleepUntil(replyAt);
		QuoteBody quote = new QuoteBody(rfq.messageId(), desk.name(), desk.simulated(),
				new Money(offer.unitPrice(), offer.item().currency()), offer.quantity(), offer.available(),
				offer.item().unit(), offer.deliveryDate(), clock.instant().plus(QUOTE_VALID_FOR), note.join());
		log.info("Quoted RFQ {} from {}: {} {} at {} {}", rfq.messageId(), rfq.from(), offer.quantity(),
				offer.item().unit().wireName(), offer.unitPrice(), offer.item().currency());
		return reply(rfq, AgentEnvelope.Type.QUOTE, quote);
	}

	AgentEnvelope reject(AgentEnvelope to, String reason) {
		String text = reason.length() > RejectBody.MAX_REASON ? reason.substring(0, RejectBody.MAX_REASON) : reason;
		return reply(to, AgentEnvelope.Type.REJECT, new RejectBody(to.messageId(), text));
	}

	private AgentEnvelope reply(AgentEnvelope to, AgentEnvelope.Type type, Object body) {
		return new AgentEnvelope(UUID.randomUUID(), type, desk.domain(), to.from(), to.conversationId(),
				Instant.now(clock), json.tree(body));
	}

	/** SIMULATED suppliers only: a random delay within the persona's range. */
	private Duration delay(Persona persona) {
		if (!desk.simulated() || persona.maxDelay().isZero()) {
			return Duration.ZERO;
		}
		return Duration.ofMillis(ThreadLocalRandom.current()
			.nextLong(persona.minDelay().toMillis(), persona.maxDelay().toMillis() + 1));
	}

	private static void sleepUntil(long nanoTime) {
		long wait = nanoTime - System.nanoTime();
		if (wait <= 0) {
			return;
		}
		try {
			Thread.sleep(Duration.ofNanos(wait));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@PreDestroy
	void shutdown() {
		noteWriters.shutdownNow();
	}

}
