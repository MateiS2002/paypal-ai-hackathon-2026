package com.tradedesk.desk.need;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.contract.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 acceptance criterion 1 against the real model. Costs a few cents, so it only runs on demand:
 * {@code RUN_LIVE_LLM=true OPENAI_API_KEY=... ./mvnw test -Dtest=NeedParserLiveTest}.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_LLM", matches = "true")
@TestPropertySource(properties = { "desk.id=cafe", "desk.name=Corner Café", "desk.domain=cafe.test",
		"desk.location=37.7793,-122.4193", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password", "desk.invoice-poll-interval=PT1H" })
class NeedParserLiveTest {

	static final String DEMO = "50 kg Brazilian beans by Friday, prioritise speed";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	NeedParser parser;

	@Autowired
	DeskProperties desk;

	@Test
	void demoSentenceParsesCorrectlyNineTimesOutOfTen() {
		LocalDate today = LocalDate.now(desk.timeZone());
		// "By Friday" on a Friday means next week's.
		LocalDate friday = today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
		int correct = 0;
		for (int i = 0; i < 10; i++) {
			ParseResult result = parser.parse(DEMO);
			PurchaseRequest r = result.request();
			boolean ok = result.complete() && r.quantity().compareTo(java.math.BigDecimal.valueOf(50)) == 0
					&& r.unit() == Unit.KG && "Brazil".equalsIgnoreCase(r.origin()) && friday.equals(r.deliverBy())
					&& r.priority() == Priority.SPEED && r.product().toLowerCase().contains("bean");
			System.out.printf("run %d: %s -> %s%n", i + 1, ok ? "ok" : "WRONG", r);
			correct += ok ? 1 : 0;
		}
		assertThat(correct).isGreaterThanOrEqualTo(9);
	}

	@Test
	void vagueSentenceAsksForQuantityAndDate() {
		ParseResult result = parser.parse("some beans");

		System.out.println("vague: " + result);
		assertThat(result.complete()).isFalse();
		assertThat(result.missing()).contains("quantity", "deliverBy");
		assertThat(result.question()).isNotBlank();
	}

}
