package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks a need's quotes for the café's priority, in Java (S3's recommendation must pick the same winner). Full offers
 * always come before partial ones; within each group:
 * <ul>
 * <li>{@code PRICE}: lowest unit price, then earliest delivery</li>
 * <li>{@code SPEED}: earliest delivery, then lowest unit price</li>
 * <li>{@code BALANCED}: lowest score, half the price's position between the cheapest and dearest quote plus half the
 * delivery's position between the earliest and latest; ties by unit price</li>
 * </ul>
 * The supplier's domain breaks any remaining tie, so the order never depends on arrival time.
 */
public final class QuoteRanking {

	private static final BigDecimal HALF = new BigDecimal("0.5");

	private QuoteRanking() {
	}

	/** The rows best first, with {@code rank} set from 1. */
	public static List<QuoteRow> rank(List<QuoteRow> quotes, Priority priority) {
		Comparator<QuoteRow> byPrice = Comparator.comparing(q -> q.unitPrice().amount());
		Comparator<QuoteRow> byDelivery = Comparator.comparing(QuoteRow::deliveryDate);
		Comparator<QuoteRow> within = switch (priority) {
			case PRICE -> byPrice.thenComparing(byDelivery);
			case SPEED -> byDelivery.thenComparing(byPrice);
			case BALANCED -> balanced(quotes).thenComparing(byPrice).thenComparing(byDelivery);
		};
		Comparator<QuoteRow> order = Comparator.comparing(QuoteRow::partial)
			.thenComparing(within)
			.thenComparing(q -> q.supplier().domain());
		List<QuoteRow> sorted = new ArrayList<>(quotes);
		sorted.sort(order);
		List<QuoteRow> ranked = new ArrayList<>(sorted.size());
		for (int i = 0; i < sorted.size(); i++) {
			ranked.add(sorted.get(i).withRank(i + 1));
		}
		return List.copyOf(ranked);
	}

	private static Comparator<QuoteRow> balanced(List<QuoteRow> quotes) {
		BigDecimal minPrice = quotes.stream().map(q -> q.unitPrice().amount()).min(BigDecimal::compareTo)
			.orElse(BigDecimal.ZERO);
		BigDecimal maxPrice = quotes.stream().map(q -> q.unitPrice().amount()).max(BigDecimal::compareTo)
			.orElse(BigDecimal.ZERO);
		var earliest = quotes.stream().map(QuoteRow::deliveryDate).min(Comparator.naturalOrder()).orElse(null);
		var latest = quotes.stream().map(QuoteRow::deliveryDate).max(Comparator.naturalOrder()).orElse(null);
		return Comparator.comparing(q -> {
			BigDecimal price = position(q.unitPrice().amount().subtract(minPrice), maxPrice.subtract(minPrice));
			BigDecimal delivery = earliest == null ? BigDecimal.ZERO
					: position(BigDecimal.valueOf(ChronoUnit.DAYS.between(earliest, q.deliveryDate())),
							BigDecimal.valueOf(ChronoUnit.DAYS.between(earliest, latest)));
			return HALF.multiply(price).add(HALF.multiply(delivery));
		});
	}

	/** {@code offset / range} between 0 and 1, or 0 when every quote is the same. */
	private static BigDecimal position(BigDecimal offset, BigDecimal range) {
		return range.signum() == 0 ? BigDecimal.ZERO : offset.divide(range, 6, RoundingMode.HALF_UP);
	}

}
