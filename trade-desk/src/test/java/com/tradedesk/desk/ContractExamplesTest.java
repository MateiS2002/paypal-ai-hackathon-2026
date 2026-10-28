package com.tradedesk.desk;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import org.junit.jupiter.api.Test;
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
	void moneyRejectsBadCurrency() {
		assertThatThrownBy(() -> new Money(BigDecimal.ONE, "usd")).isInstanceOf(IllegalArgumentException.class);
	}

}
