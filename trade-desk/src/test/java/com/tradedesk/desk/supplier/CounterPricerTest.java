package com.tradedesk.desk.supplier;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S3 acceptance criterion 4: a supplier never accepts a counter below its floor, whatever the buyer asks. */
class CounterPricerTest {

	static final BigDecimal QUOTED = new BigDecimal("11.40");

	static final BigDecimal FLOOR = new BigDecimal("10.80");

	static final BigDecimal ROASTER = Persona.ROASTER.maxConcession();

	private static BigDecimal revise(String target, BigDecimal concession) {
		return CounterPricer.revise(QUOTED, FLOOR, new BigDecimal(target), concession);
	}

	@Test
	void neverGoesBelowTheFloorForAnyTarget() {
		for (Persona persona : Persona.values()) {
			for (int cents = 1; cents <= 1200; cents++) {
				BigDecimal target = BigDecimal.valueOf(cents, 2);
				BigDecimal revised = CounterPricer.revise(QUOTED, FLOOR, target, persona.maxConcession());

				assertThat(revised).as("%s at target %s", persona, target)
					.isGreaterThanOrEqualTo(FLOOR)
					.isLessThanOrEqualTo(QUOTED);
				assertThat(revised.scale()).isEqualTo(2);
			}
		}
	}

	@Test
	void evenWithAnUnlimitedConcessionTheFloorHolds() {
		assertThat(revise("0.01", BigDecimal.ONE)).isEqualByComparingTo(FLOOR);
	}

	@Test
	void meetsATargetWithinTheConcessionAndTheFloor() {
		// 5% off 11.40 is 10.83: within the roaster's 6% and above its 10.80 floor.
		assertThat(revise("10.83", ROASTER)).isEqualByComparingTo("10.83");
	}

	@Test
	void movesPartOfTheWayWhenTheTargetIsBelowTheFloor() {
		assertThat(revise("9.50", ROASTER)).isEqualByComparingTo("10.80");
	}

	@Test
	void movesNoMoreThanThePersonaAllows() {
		// 3% off 11.40 is 11.058, rounded up to 11.06 so the concession never exceeds 3%.
		assertThat(revise("9.50", Persona.CHEAP_SLOW.maxConcession())).isEqualByComparingTo("11.06");
	}

	@Test
	void aPersonaThatNeverMovesHolds() {
		assertThat(revise("10.83", Persona.FAST_PRICEY.maxConcession())).isEqualByComparingTo("11.40");
	}

	@Test
	void aTargetAtOrAboveTheQuoteHolds() {
		assertThat(revise("11.40", ROASTER)).isEqualByComparingTo("11.40");
		assertThat(revise("99.00", ROASTER)).isEqualByComparingTo("11.40");
	}

	@Test
	void theRevisedNoteOnlyRestatesJavasNumbers() {
		SupplierQuote quote = new SupplierQuote(null, null, "cafe.test", null, "RST-BR-SANTOS", QUOTED, "USD",
				new BigDecimal("50"), new BigDecimal("320"), com.tradedesk.desk.contract.Unit.KG, null, null, "", null,
				SupplierQuote.Status.OPEN, null);

		assertThat(CounterHandler.note(new BigDecimal("10.83"), new BigDecimal("10.83"), quote))
			.isEqualTo("We can meet 10.83 USD per kg.");
		assertThat(CounterHandler.note(new BigDecimal("10.80"), new BigDecimal("9.50"), quote))
			.isEqualTo("Best we can do is 10.80 USD per kg (was 11.40).");
		assertThat(CounterHandler.note(QUOTED, new BigDecimal("10.83"), quote))
			.isEqualTo("Our price of 11.40 USD per kg holds.");
	}

}
