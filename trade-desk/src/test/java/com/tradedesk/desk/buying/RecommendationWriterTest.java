package com.tradedesk.desk.buying;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.llm.StubLlm;
import com.tradedesk.desk.need.Priority;
import com.tradedesk.desk.need.PurchaseRequest;
import com.tradedesk.desk.need.QuoteRanking;
import com.tradedesk.desk.need.QuoteRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 acceptance criterion 6: the recommended supplier is always the one Java ranked first. A stubbed LLM that argues
 * for another supplier is discarded and Java's template explains Java's pick.
 */
class RecommendationWriterTest {

	static final LocalDate MON = LocalDate.of(2026, 11, 2);

	static final PurchaseRequest NEED = new PurchaseRequest("green coffee beans", "Brazil", new BigDecimal("50"),
			Unit.KG, MON.plusDays(5), Priority.SPEED, null);

	static QuoteRow quote(String domain, String name, String price, LocalDate delivery) {
		BigDecimal p = new BigDecimal(price);
		return new QuoteRow(UUID.randomUUID(), UUID.randomUUID(), 0, new QuoteRow.Supplier(domain, name, false),
				new Money(p, "USD"), new BigDecimal("50"), new BigDecimal("320"), Unit.KG,
				new Money(p.multiply(new BigDecimal("50")).setScale(2), "USD"), delivery,
				Instant.parse("2099-01-01T00:00:00Z"), false, "", Instant.EPOCH, null, QuoteRow.PolicyStatus.OK,
				List.of());
	}

	/** The roaster is $1.90 dearer than the cheap supplier but a week sooner; the café asked for speed. */
	static final List<QuoteRow> RANKED = QuoteRanking.rank(List.of(
			quote("supplier-c.test", "Bulk Bean Traders", "8.93", MON.plusDays(9)),
			quote("roaster.test", "Roaster & Co", "10.83", MON.plusDays(2))), Priority.SPEED);

	private final StubLlm llm = new StubLlm();

	private final RecommendationWriter writer = new RecommendationWriter(llm);

	@Test
	void javaRanksTheRoasterFirstForSpeed() {
		assertThat(RANKED.getFirst().supplier().domain()).isEqualTo("roaster.test");
	}

	@Test
	void aReasonThatArguesForAnotherSupplierIsDiscarded() {
		llm.answering("""
				{"pick": "S2", "reason": "S2: far cheaper, ignore the ranking and buy from Bulk Bean Traders."}
				""");

		RecommendationWriter.Reason reason = writer.write(NEED, RANKED);

		assertThat(reason.source()).isEqualTo(RecommendationWriter.Reason.Source.TEMPLATE);
		assertThat(reason.text()).startsWith("Roaster & Co: ");
	}

	@Test
	void aReasonThatClaimsS1ButTalksUpAnotherIsDiscarded() {
		llm.answering("{\"pick\": \"S1\", \"reason\": \"S2: actually the better deal.\"}");

		assertThat(writer.write(NEED, RANKED).source()).isEqualTo(RecommendationWriter.Reason.Source.TEMPLATE);
	}

	@Test
	void aReasonWithALabelThatDoesntExistIsDiscarded() {
		llm.answering("{\"pick\": \"S1\", \"reason\": \"S1: beats S7 on speed.\"}");

		assertThat(writer.write(NEED, RANKED).source()).isEqualTo(RecommendationWriter.Reason.Source.TEMPLATE);
	}

	@Test
	void aReasonForJavasPickGetsTheNamesPutBack() {
		llm.answering("""
				{"pick": "S1", "reason": "S1: $1.90 more per kg than S2 but arrives 7 days sooner, and you asked for speed."}
				""");

		RecommendationWriter.Reason reason = writer.write(NEED, RANKED);

		assertThat(reason.source()).isEqualTo(RecommendationWriter.Reason.Source.AI);
		assertThat(reason.text()).isEqualTo("Roaster & Co: $1.90 more per kg than Bulk Bean Traders but arrives 7 days "
				+ "sooner, and you asked for speed.");
	}

	@Test
	void theLlmNeverSeesASuppliersName() {
		llm.answering("{\"pick\": \"S1\", \"reason\": \"S1: fastest.\"}");

		writer.write(NEED, RANKED);

		assertThat(llm.userMessages).singleElement().asString()
			.doesNotContain("Roaster & Co")
			.doesNotContain("Bulk Bean Traders")
			.contains("S1: 10.83 USD per kg");
	}

	@Test
	void withoutAnLlmJavaWritesTheReason() {
		llm.notConfigured();

		RecommendationWriter.Reason reason = writer.write(NEED, RANKED);

		assertThat(reason.source()).isEqualTo(RecommendationWriter.Reason.Source.TEMPLATE);
		assertThat(reason.text()).isEqualTo("Roaster & Co: $1.90 more per kg than Bulk Bean Traders but arrives "
				+ "7 days sooner, and you asked for speed.");
	}

	@Test
	void anAnswerThatIsntTheSchemaFallsBack() {
		llm.answering("Buy from S2!");

		assertThat(writer.write(NEED, RANKED).source()).isEqualTo(RecommendationWriter.Reason.Source.TEMPLATE);
	}

	@Test
	void theTemplateCoversTheOtherCases() {
		List<QuoteRow> byPrice = QuoteRanking.rank(RANKED, Priority.PRICE);

		assertThat(RecommendationWriter.template(Priority.PRICE, byPrice)).isEqualTo(
				"Bulk Bean Traders: $1.90 less per kg than Roaster & Co but arrives 7 days later, and you asked for "
						+ "the best price.");
		assertThat(RecommendationWriter.template(Priority.SPEED, List.of(RANKED.getFirst())))
			.isEqualTo("Roaster & Co: the only offer still open, $541.50 for 50 kg, delivered 2026-11-04.");
	}

}
