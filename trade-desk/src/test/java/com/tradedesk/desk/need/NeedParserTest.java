package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.llm.LlmException;
import com.tradedesk.desk.llm.LlmNotConfiguredException;
import com.tradedesk.desk.llm.StubLlm;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Java half of the buyer agent, with the LLM stubbed: what it proposes is checked, never trusted. */
class NeedParserTest {

	/** Monday 2026-11-02, 10:00 in San Francisco. */
	static final Clock MONDAY = Clock.fixed(Instant.parse("2026-11-02T18:00:00Z"), ZoneOffset.UTC);

	static final DeskProperties CAFE = new DeskProperties("cafe", "Corner Café", "cafe.test", "37.7793,-122.4193",
			"US-CA", ZoneId.of("America/Los_Angeles"), null, null, false, "pw");

	private final StubLlm llm = new StubLlm();

	private final NeedParser parser = new NeedParser(llm, CAFE, MONDAY);

	private static String proposal(String product, String origin, String quantity, String unit, String deliverBy,
			String priority, String maxUnitPrice) {
		return """
				{"product": %s, "origin": %s, "quantity": %s, "unit": %s, "deliverBy": %s, "priority": %s, "maxUnitPrice": %s}
				""".formatted(q(product), q(origin), q(quantity), q(unit), q(deliverBy), q(priority), q(maxUnitPrice));
	}

	private static String q(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	@Test
	void demoSentenceBecomesACompleteRequest() {
		llm.answering(proposal("green coffee beans", "Brazil", "50", "kg", "2026-11-06", "SPEED", null));

		ParseResult result = parser.parse("50 kg Brazilian beans by Friday, prioritise speed");

		assertThat(result.complete()).isTrue();
		assertThat(result.missing()).isEmpty();
		assertThat(result.question()).isNull();
		PurchaseRequest request = result.request();
		assertThat(request.product()).isEqualTo("green coffee beans");
		assertThat(request.origin()).isEqualTo("Brazil");
		assertThat(request.quantity()).isEqualByComparingTo("50");
		assertThat(request.unit()).isEqualTo(Unit.KG);
		assertThat(request.deliverBy()).isEqualTo(LocalDate.of(2026, 11, 6));
		assertThat(request.priority()).isEqualTo(Priority.SPEED);
		// The result is accepted by the same check the POST route runs.
		assertThat(PurchaseRequestRules.check(request, LocalDate.of(2026, 11, 2))).isEqualTo(request);
	}

	@Test
	void vagueSentenceAsksInsteadOfTrustingAGuessedQuantityAndDate() {
		// The model "helpfully" invents 10 kg by next week; the text states neither.
		llm.answering(proposal("green coffee beans", null, "10", "kg", "2026-11-09", null, null));

		ParseResult result = parser.parse("some beans");

		assertThat(result.complete()).isFalse();
		assertThat(result.request().quantity()).isNull();
		assertThat(result.request().deliverBy()).isNull();
		assertThat(result.missing()).containsExactly("quantity", "unit", "deliverBy");
		assertThat(result.question()).isEqualTo("How much (in kg or lb) and by when?");
		assertThat(result.request().priority()).isEqualTo(Priority.BALANCED);
	}

	@Test
	void aBudgetIsKeptOnlyWhenTheTextStatesIt() {
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-06", "PRICE", "15.00"));

		assertThat(parser.parse("50 kg beans by Friday, cheapest, max $15/kg").request().maxUnitPrice().amount())
			.isEqualByComparingTo("15.00");
		assertThat(parser.parse("50 kg beans by Friday, cheapest").request().maxUnitPrice()).isNull();
	}

	@Test
	void aUnitIsKeptOnlyWhenTheTextNamesIt() {
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-06", null, null));

		assertThat(parser.parse("50 beans by Friday").missing()).containsExactly("unit");
		assertThat(parser.parse("50kg beans by Friday").request().unit()).isEqualTo(Unit.KG);
		assertThat(parser.parse("50 kilos of beans by Friday").request().unit()).isEqualTo(Unit.KG);

		llm.answering(proposal("beans", null, "100", "lb", "2026-11-06", null, null));
		assertThat(parser.parse("100 pounds of beans by Friday").request().unit()).isEqualTo(Unit.LB);
		// The model says lb, the owner wrote kg: the unit is a question, not the model's pick.
		assertThat(parser.parse("100 kg of beans by Friday").missing()).containsExactly("unit");
	}

	@Test
	void theQuantityCannotBecomeABudget() {
		// The model reuses the quantity 50 as a budget; only the 15 next to "$" is a price.
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-06", null, "50"));
		assertThat(parser.parse("50 kg beans by Friday").request().maxUnitPrice()).isNull();
		assertThat(parser.parse("50 kg beans by Friday, max $15/kg").request().maxUnitPrice()).isNull();

		llm.answering(proposal("beans", null, "50", "kg", "2026-11-06", null, "15"));
		assertThat(parser.parse("50 kg beans by Friday, under 15 USD per kg").request().maxUnitPrice().amount())
			.isEqualByComparingTo("15.00");
	}

	@Test
	void hourDeadlinesAreDateCues() {
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-04", null, null));

		assertThat(parser.parse("50 kg beans within 48 hours").request().deliverBy())
			.isEqualTo(LocalDate.of(2026, 11, 4));
		assertThat(parser.parse("50 kg beans in 48hrs").request().deliverBy()).isNotNull();
	}

	@Test
	void numberWordsCountAsStated() {
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-06", null, null));

		assertThat(parser.parse("fifty kilos of beans by friday").request().quantity()).isEqualByComparingTo("50");
	}

	@Test
	void aQuantityIsNotADateCue() {
		// The text has a number (the quantity) but names no date; the model's date is a guess.
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-09", null, null));

		ParseResult result = parser.parse("50 kg beans");

		assertThat(result.request().deliverBy()).isNull();
		assertThat(result.missing()).containsExactly("deliverBy");
		// A decimal isn't a date shape either.
		assertThat(parser.parse("12.5 kg beans").missing()).contains("deliverBy");
	}

	@Test
	void dateShapesAndRelativeCountsAreDateCues() {
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-05", null, null));

		assertThat(parser.parse("50 kg beans in 3 days").request().deliverBy()).isEqualTo(LocalDate.of(2026, 11, 5));
		assertThat(parser.parse("50 kg beans by 11/5").request().deliverBy()).isEqualTo(LocalDate.of(2026, 11, 5));
		assertThat(parser.parse("50 kg beans by 2026-11-05").request().deliverBy()).isNotNull();
		assertThat(parser.parse("50 kg beans by the 5th").request().deliverBy()).isNotNull();
		assertThat(parser.parse("50 kg beans by 5.11.2026").request().deliverBy()).isNotNull();
	}

	@Test
	void datesInThePastOrUnparseableAreMissing() {
		llm.answering(proposal("beans", null, "50", "kg", "2026-11-01", null, null));
		assertThat(parser.parse("50 kg beans by yesterday 1st").missing()).containsExactly("deliverBy");

		llm.answering(proposal("beans", null, "50", "kg", "next friday", null, null));
		assertThat(parser.parse("50 kg beans by next friday").missing()).containsExactly("deliverBy");
	}

	@Test
	void outOfRangeOrBadFieldsBecomeQuestions() {
		llm.answering(proposal("beans", null, "-5", "tonnes", "2026-11-06", "URGENT", null));

		ParseResult result = parser.parse("-5 tonnes of beans by friday");

		assertThat(result.missing()).containsExactly("quantity", "unit");
		assertThat(result.request().priority()).isEqualTo(Priority.BALANCED);
	}

	@Test
	void answerWithExtraFieldsOrNotJsonIsRefused() {
		llm.answering("""
				{"product": "beans", "origin": null, "quantity": "50", "unit": "kg", "deliverBy": "2026-11-06",
				 "priority": "SPEED", "maxUnitPrice": null, "approve": true}
				""");
		assertThatThrownBy(() -> parser.parse("50 kg beans by friday")).isInstanceOf(LlmException.class);

		llm.answering("Sure! Here is your order: 50 kg");
		assertThatThrownBy(() -> parser.parse("50 kg beans by friday")).isInstanceOf(LlmException.class);
	}

	@Test
	void emptyOrHugeTextIsRefusedBeforeTheModelIsCalled() {
		assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(InvalidNeedException.class);
		assertThatThrownBy(() -> parser.parse("x".repeat(NeedParser.MAX_TEXT + 1)))
			.isInstanceOf(InvalidNeedException.class);
		assertThat(llm.userMessages).isEmpty();
	}

	@Test
	void withoutAKeyItSaysSo() {
		llm.notConfigured();

		assertThatThrownBy(() -> parser.parse("50 kg beans")).isInstanceOf(LlmNotConfiguredException.class);
	}

	@Test
	void mentionsReadsDigitsCommasAndWords() {
		assertThat(NeedParser.mentions("need 12.5kg", new BigDecimal("12.500"))).isTrue();
		assertThat(NeedParser.mentions("need 12,5 kg", new BigDecimal("12.5"))).isTrue();
		assertThat(NeedParser.mentions("need 1,500 lb", new BigDecimal("1500"))).isTrue();
		assertThat(NeedParser.mentions("a dozen bags", new BigDecimal("12"))).isTrue();
		assertThat(NeedParser.mentions("some beans", new BigDecimal("10"))).isFalse();
	}

}
