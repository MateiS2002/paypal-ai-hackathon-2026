package com.tradedesk.desk.supplier;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.tradedesk.desk.agent.RfqBody;
import com.tradedesk.desk.contract.Unit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The supplier's money rules: never below the floor, never above stock, for every seeded persona. */
class QuotePricerTest {

	static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

	static final CatalogueItem SANTOS = new CatalogueItem("RST-BR-SANTOS", "Santos natural, Cerrado Mineiro",
			"green coffee beans", "Brazil", Unit.KG, "USD", new BigDecimal("12.00"), new BigDecimal("10.80"),
			new BigDecimal("320"), 2);

	static RfqBody rfq(String product, String origin, String quantity, Unit unit) {
		return new RfqBody(product, origin, new BigDecimal(quantity), unit, TODAY.plusDays(4));
	}

	static List<CatalogueItem> seed(Persona persona) throws IOException {
		return new CatalogueSeeder(null, null, JsonMapper.builder().build()).load(persona);
	}

	@Test
	void quotesListPriceLessVolumeDiscountForTheDemoNeed() {
		QuotePricer.Offer offer = (QuotePricer.Offer) QuotePricer.price(rfq("green coffee beans", "Brazil", "50",
				Unit.KG), List.of(SANTOS), TODAY);

		// 12.00 less 5% = 11.40, above the 10.80 floor.
		assertThat(offer.unitPrice()).isEqualByComparingTo("11.40");
		assertThat(offer.quantity()).isEqualByComparingTo("50");
		assertThat(offer.available()).isEqualByComparingTo("320");
		assertThat(offer.deliveryDate()).isEqualTo(TODAY.plusDays(2));
		assertThat(offer.partial()).isFalse();
	}

	@Test
	void discountNeverGoesBelowTheFloor() {
		CatalogueItem thinMargin = new CatalogueItem("X", "Santos", "green coffee beans", "Brazil", Unit.KG, "USD",
				new BigDecimal("10.00"), new BigDecimal("9.90"), new BigDecimal("1000"), 2);

		QuotePricer.Offer offer = (QuotePricer.Offer) QuotePricer.price(rfq("beans", null, "500", Unit.KG),
				List.of(thinMargin), TODAY);

		// 10.00 less 8% would be 9.20.
		assertThat(offer.unitPrice()).isEqualByComparingTo("9.90");
	}

	@Test
	void neverBelowFloorOrAboveStockForAnyPersonaOrQuantity() throws IOException {
		String[] quantities = { "0.001", "1", "19.999", "20", "21", "49.999", "50", "199", "200", "320", "321", "2500",
				"99999.999" };
		for (Persona persona : Persona.values()) {
			for (CatalogueItem item : seed(persona)) {
				for (String quantity : quantities) {
					QuotePricer.Decision decision = QuotePricer.price(rfq("green coffee beans", item.origin(),
							quantity, item.unit()), List.of(item), TODAY);
					QuotePricer.Offer offer = (QuotePricer.Offer) decision;
					assertThat(offer.unitPrice()).as("%s %s", item.sku(), quantity)
						.isGreaterThanOrEqualTo(item.floorPrice());
					assertThat(offer.quantity()).isLessThanOrEqualTo(item.stock())
						.isLessThanOrEqualTo(new BigDecimal(quantity));
					assertThat(offer.partial()).isEqualTo(new BigDecimal(quantity).compareTo(item.stock()) > 0);
				}
			}
		}
	}

	@Test
	void lowStockOffersAllItHas() throws IOException {
		QuotePricer.Offer offer = (QuotePricer.Offer) QuotePricer.price(rfq("green coffee beans", "Brazil", "50",
				Unit.KG), seed(Persona.LOW_STOCK), TODAY);

		assertThat(offer.quantity()).isEqualByComparingTo("20");
		assertThat(offer.partial()).isTrue();
	}

	@Test
	void prefersAnItemThatCoversTheQuantityThenTheCheapest() {
		CatalogueItem small = new CatalogueItem("S", "Small lot", "green coffee beans", "Brazil", Unit.KG, "USD",
				new BigDecimal("8.00"), new BigDecimal("7.00"), new BigDecimal("10"), 1);

		QuotePricer.Offer offer = (QuotePricer.Offer) QuotePricer.price(rfq("beans", "Brazil", "50", Unit.KG),
				List.of(small, SANTOS), TODAY);

		assertThat(offer.item().sku()).isEqualTo("RST-BR-SANTOS");
	}

	@Test
	void refusesWhatItDoesntSell() {
		assertThat(QuotePricer.price(rfq("oat milk", null, "10", Unit.KG), List.of(SANTOS), TODAY))
			.isInstanceOf(QuotePricer.Refusal.class);
		assertThat(QuotePricer.price(rfq("green coffee beans", "Kenya", "10", Unit.KG), List.of(SANTOS), TODAY))
			.isInstanceOf(QuotePricer.Refusal.class);
		assertThat(QuotePricer.price(rfq("green coffee beans", "Brazil", "10", Unit.LB), List.of(SANTOS), TODAY))
			.isEqualTo(new QuotePricer.Refusal("We only sell this by the kg."));
		CatalogueItem empty = new CatalogueItem("E", "Santos", "green coffee beans", "Brazil", Unit.KG, "USD",
				new BigDecimal("12.00"), new BigDecimal("10.00"), BigDecimal.ZERO, 2);
		assertThat(QuotePricer.price(rfq("beans", null, "10", Unit.KG), List.of(empty), TODAY))
			.isEqualTo(new QuotePricer.Refusal("Out of stock."));
	}

	@Test
	void refusesMalformedRfqs() {
		assertThat(QuotePricer.price(rfq("beans", null, "-1", Unit.KG), List.of(SANTOS), TODAY))
			.isInstanceOf(QuotePricer.Refusal.class);
		assertThat(QuotePricer.price(rfq("beans", null, "1.0001", Unit.KG), List.of(SANTOS), TODAY))
			.isInstanceOf(QuotePricer.Refusal.class);
		assertThat(QuotePricer.price(new RfqBody("beans", null, BigDecimal.TEN, null, TODAY), List.of(SANTOS), TODAY))
			.isInstanceOf(QuotePricer.Refusal.class);
		assertThat(QuotePricer.price(new RfqBody("", null, BigDecimal.TEN, Unit.KG, TODAY), List.of(SANTOS), TODAY))
			.isInstanceOf(QuotePricer.Refusal.class);
	}

	@Test
	void originMatchesAdjectivesAndAny() {
		assertThat(QuotePricer.sameOrigin("Brazilian", "Brazil")).isTrue();
		assertThat(QuotePricer.sameOrigin(null, "Brazil")).isTrue();
		assertThat(QuotePricer.sameOrigin("Colombia", "Brazil")).isFalse();
	}

	@Test
	void everySeedFilePassesTheSeedRules() throws IOException {
		for (Persona persona : Persona.values()) {
			assertThat(seed(persona)).isNotEmpty().allSatisfy(CatalogueSeeder::check);
		}
	}

}
