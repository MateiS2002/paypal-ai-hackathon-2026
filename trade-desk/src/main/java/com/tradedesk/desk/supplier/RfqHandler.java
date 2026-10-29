package com.tradedesk.desk.supplier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.agent.RejectBody;
import com.tradedesk.desk.agent.RfqBody;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
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

	private static final Logger log = LoggerFactory.getLogger(RfqHandler.class);

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
		LocalDate today = LocalDate.now(clock.withZone(desk.timeZone()));
		QuotePricer.Decision decision = QuotePricer.price(body, catalogue.findAll(), today);
		pause(persona);
		if (decision instanceof QuotePricer.Refusal refusal) {
			log.info("Rejected RFQ {} from {}: {}", rfq.messageId(), rfq.from(), refusal.reason());
			return reject(rfq, refusal.reason());
		}
		QuotePricer.Offer offer = (QuotePricer.Offer) decision;
		String note = notes.write(offer, persona);
		QuoteBody quote = new QuoteBody(rfq.messageId(), desk.name(), desk.simulated(),
				new Money(offer.unitPrice(), offer.item().currency()), offer.quantity(), offer.available(),
				offer.item().unit(), offer.deliveryDate(), clock.instant().plus(QUOTE_VALID_FOR), note);
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
	private void pause(Persona persona) {
		if (!desk.simulated() || persona.maxDelay().isZero()) {
			return;
		}
		long millis = ThreadLocalRandom.current()
			.nextLong(persona.minDelay().toMillis(), persona.maxDelay().toMillis() + 1);
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
