package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S2 acceptance criterion 2, the ranking half: the right order for every priority. */
class QuoteRankingTest {

	static final LocalDate MON = LocalDate.of(2026, 11, 2);

	/** The demo's four suppliers, roughly as seeded. */
	static final QuoteRow ROASTER = row("roaster.test", "11.40", MON.plusDays(2), false);

	static final QuoteRow FAST = row("supplier-b.test", "13.49", MON.plusDays(1), false);

	static final QuoteRow CHEAP = row("supplier-c.test", "8.93", MON.plusDays(9), false);

	static final QuoteRow LOW = row("supplier-d.test", "11.20", MON.plusDays(3), true);

	static QuoteRow row(String domain, String price, LocalDate delivery, boolean partial) {
		return new QuoteRow(UUID.randomUUID(), UUID.randomUUID(), 0, new QuoteRow.Supplier(domain, domain, false),
				new Money(new BigDecimal(price), "USD"), BigDecimal.TEN, BigDecimal.TEN, Unit.KG,
				new Money(new BigDecimal(price).multiply(BigDecimal.TEN), "USD"), delivery,
				Instant.parse("2099-01-01T00:00:00Z"), partial, "", Instant.EPOCH);
	}

	private static List<String> order(Priority priority) {
		return QuoteRanking.rank(List.of(LOW, CHEAP, ROASTER, FAST), priority)
			.stream()
			.map(q -> q.supplier().domain())
			.toList();
	}

	@Test
	void speedPutsEarliestDeliveryFirst() {
		assertThat(order(Priority.SPEED)).containsExactly("supplier-b.test", "roaster.test", "supplier-c.test",
				"supplier-d.test");
	}

	@Test
	void pricePutsCheapestFirst() {
		assertThat(order(Priority.PRICE)).containsExactly("supplier-c.test", "roaster.test", "supplier-b.test",
				"supplier-d.test");
	}

	@Test
	void balancedWeighsBothEqually() {
		// Prices 8.93..13.49, days 1..9: roaster 0.271+0.063=0.33, fast 0.5+0=0.5, cheap 0+0.5=0.5 (cheaper wins).
		assertThat(order(Priority.BALANCED)).containsExactly("roaster.test", "supplier-c.test", "supplier-b.test",
				"supplier-d.test");
	}

	@Test
	void partialOffersAlwaysComeLastEvenWhenBest() {
		QuoteRow partialBest = row("supplier-d.test", "1.00", MON.plusDays(1), true);
		for (Priority priority : Priority.values()) {
			assertThat(QuoteRanking.rank(List.of(partialBest, ROASTER), priority).getLast()).extracting(QuoteRow::partial)
				.isEqualTo(true);
		}
	}

	@Test
	void ranksStartAtOneAndTiesAreStable() {
		QuoteRow a = row("a.test", "10.00", MON.plusDays(2), false);
		QuoteRow b = row("b.test", "10.00", MON.plusDays(2), false);

		List<QuoteRow> ranked = QuoteRanking.rank(List.of(b, a), Priority.BALANCED);

		assertThat(ranked).extracting(QuoteRow::rank).containsExactly(1, 2);
		assertThat(ranked).extracting(q -> q.supplier().domain()).containsExactly("a.test", "b.test");
		assertThat(QuoteRanking.rank(List.of(), Priority.SPEED)).isEmpty();
	}

}
