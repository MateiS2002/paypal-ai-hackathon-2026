package com.tradedesk.desk.contract;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Units a need or a quote can be in: {@code kg} or {@code lb} on the wire. No conversion between them in S2. */
public enum Unit {

	KG("kg"), LB("lb");

	private final String wireName;

	Unit(String wireName) {
		this.wireName = wireName;
	}

	@JsonValue
	public String wireName() {
		return wireName;
	}

	/** Reads {@code kg} or {@code lb}, ignoring case; anything else is refused. */
	@JsonCreator
	public static Unit fromWire(String value) {
		if (value != null) {
			String normalized = value.strip().toLowerCase(Locale.ROOT);
			for (Unit unit : values()) {
				if (unit.wireName.equals(normalized)) {
					return unit;
				}
			}
		}
		throw new IllegalArgumentException("unit must be kg or lb, got " + value);
	}

}
