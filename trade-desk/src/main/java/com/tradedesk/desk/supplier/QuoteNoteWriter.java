package com.tradedesk.desk.supplier;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.llm.Llm;
import com.tradedesk.desk.llm.LlmException;
import com.tradedesk.desk.llm.LlmNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * The one thing the seller's LLM decides: a one-line note on a quote. It is called after {@link QuotePricer} has
 * fixed every number and gets those numbers as facts; whatever it writes stays display text, capped and cleaned.
 * Without a key, or if the call fails, a plain template note is used instead, so a quote never waits on the LLM.
 */
@Component
public class QuoteNoteWriter {

	static final int MAX_NOTE = 200;

	private static final Logger log = LoggerFactory.getLogger(QuoteNoteWriter.class);

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.ENGLISH);

	private final Llm llm;

	private final DeskProperties desk;

	public QuoteNoteWriter(Llm llm, DeskProperties desk) {
		this.llm = llm;
		this.desk = desk;
	}

	public String write(QuotePricer.Offer offer, Persona persona) {
		// The malicious supplier's note is its attack, fixed so the demo is deterministic; no LLM writes it.
		if (persona == Persona.MALICIOUS) {
			return Persona.INJECTION_NOTE;
		}
		if (!llm.configured()) {
			return fallback(offer);
		}
		try {
			String note = clean(llm.text(system(persona), facts(offer)));
			return note.isEmpty() ? fallback(offer) : note;
		}
		catch (LlmException | LlmNotConfiguredException ex) {
			log.info("Quote note falls back to the template: {}", ex.getClass().getSimpleName());
			return fallback(offer);
		}
	}

	static String fallback(QuotePricer.Offer offer) {
		return clean(offer.item().description() + ": " + offer.quantity().stripTrailingZeros().toPlainString() + " "
				+ offer.item().unit().wireName() + (offer.partial() ? " (all we have)" : "") + ", delivered "
				+ DAY.format(offer.deliveryDate()) + ".");
	}

	/** One line, no control characters, at most {@link #MAX_NOTE} characters. */
	static String clean(String text) {
		if (text == null) {
			return "";
		}
		// String.lines() and \p{Cntrl} miss NEL and the Unicode line and paragraph separators.
		String line = text.replaceAll("[\u0085\u2028\u2029]", " ").strip().lines().findFirst().orElse("")
			.replaceAll("\\p{Cntrl}", " ").strip();
		if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
			line = line.substring(1, line.length() - 1).strip();
		}
		return line.length() > MAX_NOTE ? line.substring(0, MAX_NOTE - 1).strip() + "…" : line;
	}

	private String system(Persona persona) {
		return """
				You write one short sentence (under 25 words) for a quote from %s, a %s, to a café buyer. \
				Mention the coffee and the delivery date. Don't state or change any price, quantity or date that \
				isn't in the facts, and don't promise anything else. Plain text, no quotes, no emojis.
				""".formatted(desk.name(), persona.tone());
	}

	/** Only Java's own facts go in; the buyer's free text never reaches this prompt. */
	private static String facts(QuotePricer.Offer offer) {
		return "Coffee: " + offer.item().description() + ". Quantity offered: "
				+ offer.quantity().stripTrailingZeros().toPlainString() + " " + offer.item().unit().wireName()
				+ (offer.partial() ? " (less than asked; it's all we have)" : "") + ". Delivery: "
				+ DAY.format(offer.deliveryDate()) + ".";
	}

}
