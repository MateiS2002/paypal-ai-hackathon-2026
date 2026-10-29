package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Unit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S2 acceptance criterion 4: a malformed quote is dropped with a reason, never shown. */
class QuoteValidatorTest {

	static final Instant NOW = Instant.parse("2026-11-02T18:00:00Z");

	static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

	static final PurchaseRequest REQUEST = new PurchaseRequest("green coffee beans", "Brazil", new BigDecimal("50.000"),
			Unit.KG, LocalDate.of(2026, 11, 6), Priority.SPEED, null);

	private final AgentJson json = new AgentJson();

	private final AgentEnvelope rfq = new AgentEnvelope(UUID.randomUUID(), AgentEnvelope.Type.RFQ, "cafe.test",
			"roaster.test", UUID.randomUUID(), NOW, json.tree(java.util.Map.of()));

	private String reply(String type, String from, String body) {
		return """
				{ "messageId": "%s", "type": "%s", "from": "%s", "to": "cafe.test", "conversationId": "%s",
				  "sentAt": "2026-11-02T18:00:01Z", "body": %s }
				""".formatted(UUID.randomUUID(), type, from, rfq.conversationId(), body);
	}

	private String quote(String price, String currency, String quantity, String extra) {
		return """
				{ "rfqId": "%s", "supplierName": "Roaster & Co", "simulated": false,
				  "unitPrice": { "amount": "%s", "currency": "%s" }, "quantity": "%s", "availableQuantity": "320",
				  "unit": "kg", "deliveryDate": "2026-11-04", "validUntil": "2026-11-03T18:00:00Z",
				  "note": "Fresh Santos." %s }
				""".formatted(rfq.messageId(), price, currency, quantity, extra);
	}

	private QuoteValidator.Outcome check(String raw) {
		return QuoteValidator.check(json, raw, rfq, "roaster.test", REQUEST, NOW, TODAY);
	}

	private String dropped(String raw) {
		QuoteValidator.Outcome outcome = check(raw);
		assertThat(outcome).isInstanceOf(QuoteValidator.Dropped.class);
		return ((QuoteValidator.Dropped) outcome).problem();
	}

	@Test
	void acceptsAValidQuote() {
		QuoteValidator.Outcome outcome = check(reply("quote", "roaster.test", quote("11.40", "USD", "50", "")));

		assertThat(outcome).isInstanceOf(QuoteValidator.Accepted.class);
		assertThat(((QuoteValidator.Accepted) outcome).quote().unitPrice().amount()).isEqualByComparingTo("11.40");
	}

	@Test
	void dropsNegativeOrZeroOrFractionalCentPrices() {
		assertThat(dropped(reply("quote", "roaster.test", quote("-1.00", "USD", "50", "")))).contains("unitPrice");
		assertThat(dropped(reply("quote", "roaster.test", quote("0.00", "USD", "50", "")))).contains("unitPrice");
		assertThat(dropped(reply("quote", "roaster.test", quote("11.405", "USD", "50", "")))).contains("unitPrice");
	}

	@Test
	void dropsTheWrongCurrency() {
		assertThat(dropped(reply("quote", "roaster.test", quote("11.40", "EUR", "50", "")))).contains("USD");
		// Not even an ISO code: fails while reading the body.
		assertThat(dropped(reply("quote", "roaster.test", quote("11.40", "usd", "50", "")))).contains("schema");
	}

	@Test
	void dropsMissingOrExtraFields() {
		String missingPrice = """
				{ "rfqId": "%s", "supplierName": "Roaster & Co", "simulated": false, "quantity": "50",
				  "availableQuantity": "320", "unit": "kg", "deliveryDate": "2026-11-04",
				  "validUntil": "2026-11-03T18:00:00Z", "note": "" }
				""".formatted(rfq.messageId());
		assertThat(dropped(reply("quote", "roaster.test", missingPrice))).contains("unitPrice");
		assertThat(dropped(reply("quote", "roaster.test", quote("11.40", "USD", "50", ", \"approved\": true"))))
			.contains("schema");
		assertThat(dropped("not json")).contains("envelope");
	}

	@Test
	void dropsOffersOfMoreThanAskedOrMoreThanInStock() {
		assertThat(dropped(reply("quote", "roaster.test", quote("11.40", "USD", "60", "")))).contains("quantity");
		assertThat(dropped(reply("quote", "roaster.test", quote("11.40", "USD", "500", "")))).contains("quantity");
	}

	@Test
	void dropsRepliesFromSomeoneElseOrForAnotherRfq() {
		assertThat(dropped(reply("quote", "supplier-b.test", quote("11.40", "USD", "50", "")))).contains("not from");
		String otherRfq = quote("11.40", "USD", "50", "").replace(rfq.messageId().toString(),
				UUID.randomUUID().toString());
		assertThat(dropped(reply("quote", "roaster.test", otherRfq))).contains("rfqId");
	}

	@Test
	void dropsExpiredQuotesAndPastDeliveries() {
		String expired = quote("11.40", "USD", "50", "").replace("2026-11-03T18:00:00Z", "2026-11-01T00:00:00Z");
		assertThat(dropped(reply("quote", "roaster.test", expired))).contains("validUntil");
		String past = quote("11.40", "USD", "50", "").replace("2026-11-04", "2026-10-30");
		assertThat(dropped(reply("quote", "roaster.test", past))).contains("deliveryDate");
	}

	@Test
	void readsAValidReject() {
		String reject = "{ \"rfqId\": \"%s\", \"reason\": \"Out of stock.\" }".formatted(rfq.messageId());

		assertThat(check(reply("reject", "roaster.test", reject))).isInstanceOf(QuoteValidator.Rejected.class);
		assertThat(dropped(reply("accept", "roaster.test", reject))).contains("not a reply");
	}

}
