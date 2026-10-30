package com.tradedesk.desk.supplier;

import java.time.Duration;
import java.util.Locale;

/**
 * Which seeded catalogue a supplier desk sells from ({@code DESK_PERSONA}), and how the SIMULATED ones behave.
 * Prices, stock and lead times live in {@code catalogue/<persona>.json}; the persona only adds a reply delay so
 * quotes visibly arrive one at a time, and a tone for the LLM's note.
 */
public enum Persona {

	/** The real roaster. Answers at once. */
	ROASTER("roaster", false, Duration.ZERO, Duration.ZERO, "friendly specialty roaster"),

	/** SIMULATED: delivers fast, charges more. */
	FAST_PRICEY("fast-pricey", true, Duration.ofMillis(500), Duration.ofMillis(1500), "brisk express importer"),

	/** SIMULATED: cheapest, slowest. */
	CHEAP_SLOW("cheap-slow", true, Duration.ofMillis(3000), Duration.ofMillis(4500), "laid-back bulk trader"),

	/** SIMULATED: small lots, often less than asked. */
	LOW_STOCK("low-stock", true, Duration.ofMillis(1800), Duration.ofMillis(2800), "small-lot importer");

	private final String wireName;

	private final boolean simulated;

	private final Duration minDelay;

	private final Duration maxDelay;

	private final String tone;

	Persona(String wireName, boolean simulated, Duration minDelay, Duration maxDelay, String tone) {
		this.wireName = wireName;
		this.simulated = simulated;
		this.minDelay = minDelay;
		this.maxDelay = maxDelay;
		this.tone = tone;
	}

	public String wireName() {
		return wireName;
	}

	/** True for SIMULATED suppliers. */
	public boolean simulated() {
		return simulated;
	}

	public Duration minDelay() {
		return minDelay;
	}

	public Duration maxDelay() {
		return maxDelay;
	}

	public String tone() {
		return tone;
	}

	/**
	 * @return the persona, or null for a blank value (a desk that sells nothing, like the café)
	 * @throws IllegalArgumentException for an unknown persona, so a typo in docker-compose.yml stops the desk
	 */
	public static Persona fromWire(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.strip().toLowerCase(Locale.ROOT);
		for (Persona persona : values()) {
			if (persona.wireName.equals(normalized)) {
				return persona;
			}
		}
		throw new IllegalArgumentException("Unknown DESK_PERSONA '" + value + "'. Use roaster, fast-pricey, "
				+ "cheap-slow or low-stock.");
	}

}
