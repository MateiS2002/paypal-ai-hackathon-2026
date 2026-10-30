package com.tradedesk.desk;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.agent.RfqBody;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.need.QuoteRow;
import com.tradedesk.desk.invoice.InvoiceRow;
import com.tradedesk.desk.invoice.InvoiceStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Keeps the Java records and the shared examples in ../contracts in step. */
class ContractExamplesTest {

	private static final Path EXAMPLES = Path.of("..", "contracts", "examples");

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void rfqEnvelopeExampleParses() throws IOException {
		AgentEnvelope envelope = json.readValue(Files.readString(EXAMPLES.resolve("envelope-rfq.json")),
				AgentEnvelope.class);

		assertThat(envelope.type()).isEqualTo(AgentEnvelope.Type.RFQ);
		assertThat(envelope.from()).isEqualTo("cafe.test");
		assertThat(envelope.body().isObject()).isTrue();
	}

	@Test
	void invoiceStatusTypeUsesItsWireName() {
		assertThat(json.writeValueAsString(AgentEnvelope.Type.INVOICE_STATUS)).isEqualTo("\"invoice.status\"");
	}

	@Test
	void moneyIsADecimalStringOnTheWire() throws IOException {
		Money money = json.readValue(Files.readString(EXAMPLES.resolve("money.json")), Money.class);

		assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("12.50"));
		assertThat(json.writeValueAsString(money)).isEqualTo("{\"amount\":\"12.50\",\"currency\":\"USD\"}");
	}

	@Test
	void invoiceUpdatedEventExampleParsesAsALedgerRow() throws IOException {
		JsonNode event = json.readTree(Files.readString(EXAMPLES.resolve("sse-invoice-updated.json")));
		InvoiceRow row = json.treeToValue(event.get("data"), InvoiceRow.class);

		assertThat(event.get("type").asString()).isEqualTo("invoice.updated");
		assertThat(row.status()).isEqualTo(InvoiceStatus.PAID);
		assertThat(row.lines().getFirst().quantity()).isEqualByComparingTo("0.333");
		assertThat(row.total().amount()).isEqualByComparingTo("5.99");
		assertThat(json.writeValueAsString(row)).contains("\"quantity\":\"0.333\"");
	}

	@Test
	void agentMessageExamplesParseStrictlyIntoTheirBodies() throws IOException {
		AgentJson agentJson = new AgentJson();
		AgentEnvelope rfq = agentJson.envelope(Files.readString(EXAMPLES.resolve("envelope-rfq.json"))).orElseThrow();
		AgentEnvelope quote = agentJson.envelope(Files.readString(EXAMPLES.resolve("envelope-quote.json")))
			.orElseThrow();

		RfqBody rfqBody = agentJson.body(rfq.body(), RfqBody.class).orElseThrow();
		QuoteBody quoteBody = agentJson.body(quote.body(), QuoteBody.class).orElseThrow();

		assertThat(rfqBody.unit()).isEqualTo(Unit.KG);
		assertThat(quote.type()).isEqualTo(AgentEnvelope.Type.QUOTE);
		assertThat(quoteBody.rfqId()).isEqualTo(rfq.messageId());
		assertThat(quoteBody.unitPrice().amount()).isEqualByComparingTo("11.40");
	}

	@Test
	void quoteReceivedExampleParsesAsQuoteRows() throws IOException {
		JsonNode event = json.readTree(Files.readString(EXAMPLES.resolve("sse-quote-received.json")));
		QuoteRow row = json.treeToValue(event.get("data").get("quotes").get(0), QuoteRow.class);

		assertThat(event.get("type").asString()).isEqualTo("quote.received");
		assertThat(row.rank()).isEqualTo(1);
		assertThat(row.total().amount()).isEqualByComparingTo("570.00");
		assertThat(json.writeValueAsString(row)).contains("\"quantity\":\"50\"");
	}

	@Test
	void everyEnvelopeExampleIsAStrictEnvelope() throws IOException {
		AgentJson agentJson = new AgentJson();
		List<Path> envelopes = examples("envelope-");

		assertThat(envelopes).hasSizeGreaterThanOrEqualTo(8);
		for (Path file : envelopes) {
			AgentEnvelope envelope = agentJson.envelope(Files.readString(file)).orElse(null);
			assertThat(envelope).as(file.getFileName().toString()).isNotNull();
			assertThat(envelope.type()).as(file.getFileName().toString()).isNotNull();
			assertThat(envelope.body().isObject()).as(file.getFileName().toString()).isTrue();
		}
	}

	@Test
	void everyEventExampleHasATypeAndData() throws IOException {
		for (Path file : examples("sse-")) {
			JsonNode event = json.readTree(Files.readString(file));
			assertThat(event.get("type").isString()).as(file.getFileName().toString()).isTrue();
			assertThat(event.get("data").isObject()).as(file.getFileName().toString()).isTrue();
		}
	}

	private static List<Path> examples(String prefix) throws IOException {
		try (Stream<Path> files = Files.list(EXAMPLES)) {
			return files.filter(f -> f.getFileName().toString().startsWith(prefix)).sorted().toList();
		}
	}

	@Test
	void moneyRejectsBadCurrency() {
		assertThatThrownBy(() -> new Money(BigDecimal.ONE, "usd")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void moneyRejectsAmountsTheSchemaRejects() {
		assertThatThrownBy(() -> new Money(new BigDecimal("1E+3"), "USD"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Money(new BigDecimal("1.23456"), "USD"))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
