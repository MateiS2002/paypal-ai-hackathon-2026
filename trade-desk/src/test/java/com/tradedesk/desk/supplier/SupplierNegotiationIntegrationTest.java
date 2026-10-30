package com.tradedesk.desk.supplier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jayway.jsonpath.JsonPath;
import com.tradedesk.desk.invoice.InvoicePoller;
import com.tradedesk.desk.llm.Llm;
import com.tradedesk.desk.llm.StubLlm;
import com.tradedesk.desk.paypal.WireMockPayPal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The roaster's half of S3 through the real {@code POST /agent/messages}, a real Postgres (Testcontainers, skipped
 * without Docker), PayPal stubbed by WireMock and the café stubbed by a second WireMock: one counter per quote and
 * never below the floor, an expired quote refused with no invoice, one invoice per quote however often it is accepted,
 * and {@code invoice.status} pushed to the buyer when the poller sees PAID.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(WireMockPayPal.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = { "desk.id=roaster", "desk.name=Roaster & Co", "desk.domain=roaster.test",
		"desk.location=37.8044,-122.2712", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password", "desk.persona=roaster",
		// The test calls the poller and the notifier itself.
		"desk.invoice-poll-interval=PT1H", "desk.buyer-notify-interval=PT1H", "paypal.client-id=test-client-id",
		"paypal.client-secret=test-secret" })
class SupplierNegotiationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	static final WireMockServer PAYPAL = WireMockPayPal.SERVER;

	static final WireMockServer CAFE = new WireMockServer(
			WireMockConfiguration.options().dynamicPort().globalTemplating(true));

	static final MutableClock CLOCK = new MutableClock();

	static {
		CAFE.start();
	}

	@DynamicPropertySource
	static void buyers(DynamicPropertyRegistry registry) {
		registry.add("desk.buyers", () -> "cafe.test=" + CAFE.baseUrl() + "/cafe");
	}

	@AfterAll
	static void stop() {
		CAFE.stop();
	}

	@TestConfiguration
	static class Stubs {

		@Bean
		@Primary
		Llm stubLlm() {
			return new StubLlm().answering("Fresh Santos lot.");
		}

		@Bean
		@Primary
		Clock testClock() {
			return CLOCK;
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	InvoicePoller poller;

	@Autowired
	BuyerNotifier notifier;

	private String payPalId;

	@BeforeEach
	void stubs() {
		CLOCK.now = Instant.now();
		payPalId = "INV2-" + UUID.randomUUID();
		PAYPAL.resetAll();
		PAYPAL.stubFor(post("/v1/oauth2/token")
			.willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
		PAYPAL.stubFor(post("/v2/invoicing/generate-next-invoice-number")
			.willReturn(okJson("{\"invoice_number\":\"0042\"}")));
		// 50 kg at the 10.80 floor: 540.00.
		PAYPAL.stubFor(post("/v2/invoicing/invoices").willReturn(okJson(invoice("DRAFT")).withStatus(201)));
		PAYPAL.stubFor(post("/v2/invoicing/invoices/" + payPalId + "/send").willReturn(okJson(
				"{\"href\":\"https://www.sandbox.paypal.com/invoice/p/#" + payPalId + "\",\"rel\":\"payer-view\"}")));
		PAYPAL.stubFor(get("/v2/invoicing/invoices/" + payPalId).willReturn(okJson(invoice("SENT"))));
		CAFE.resetAll();
		CAFE.stubFor(post("/cafe/agent/messages").willReturn(aResponse().withHeader("Content-Type", "application/json")
			.withBody("""
					{ "messageId": "{{randomValue type='UUID'}}", "type": "invoice.status", "from": "cafe.test",
					  "to": "roaster.test", "conversationId": "{{jsonPath request.body '$.conversationId'}}",
					  "sentAt": "2026-11-03T10:04:14Z",
					  "body": { "paypalInvoiceId": "{{jsonPath request.body '$.body.paypalInvoiceId'}}",
					            "status": "{{jsonPath request.body '$.body.status'}}",
					            "updatedAt": "{{jsonPath request.body '$.body.updatedAt'}}" } }
					""")));
	}

	@Test
	void oneCounterNeverBelowTheFloorThenOneInvoicePerQuote() throws Exception {
		UUID conversation = UUID.randomUUID();
		String quote = send(rfq(conversation));
		String quoteId = JsonPath.read(quote, "$.messageId");
		assertThat(JsonPath.<String>read(quote, "$.body.unitPrice.amount")).isEqualTo("11.40");

		// Criterion 4: asked for 9.00, the roaster goes no lower than its 10.80 floor.
		String revised = send(counter(conversation, quoteId, "9.00"));
		assertThat(JsonPath.<String>read(revised, "$.type")).isEqualTo("quote");
		assertThat(JsonPath.<String>read(revised, "$.body.unitPrice.amount")).isEqualTo("10.80");
		assertThat(JsonPath.<String>read(revised, "$.body.revises")).isEqualTo(quoteId);
		assertThat(JsonPath.<String>read(revised, "$.body.rfqId")).isEqualTo(JsonPath.read(quote, "$.body.rfqId"));
		String revisedId = JsonPath.read(revised, "$.messageId");

		// Exactly one counter round: neither the old quote nor the revision can be countered again.
		assertThat(type(send(counter(conversation, quoteId, "10.00")))).isEqualTo("reject");
		assertThat(type(send(counter(conversation, revisedId, "10.00")))).isEqualTo("reject");
		// The replaced quote can't be accepted, and a quote is only ever the sender's.
		assertThat(type(send(accept(conversation, quoteId, "cafe.test")))).isEqualTo("reject");
		assertThat(type(send(accept(conversation, revisedId, "supplier-b.test")))).isEqualTo("reject");
		PAYPAL.verify(0, postRequestedFor(urlEqualTo("/v2/invoicing/invoices")));

		String invoice = send(accept(conversation, revisedId, "cafe.test"));
		assertThat(type(invoice)).isEqualTo("invoice");
		assertThat(JsonPath.<String>read(invoice, "$.body.paypalInvoiceId")).isEqualTo(payPalId);
		assertThat(JsonPath.<String>read(invoice, "$.body.total.amount")).isEqualTo("540.00");
		assertThat(JsonPath.<String>read(invoice, "$.body.status")).isEqualTo("SENT");
		assertThat(JsonPath.<String>read(invoice, "$.body.payerViewUrl"))
			.isEqualTo("https://www.sandbox.paypal.com/invoice/p/#" + payPalId);

		// Accepting the same quote again, under a new message id, gives the same invoice and no second PayPal call.
		assertThat(JsonPath.<String>read(send(accept(conversation, revisedId, "cafe.test")), "$.body.paypalInvoiceId"))
			.isEqualTo(payPalId);
		PAYPAL.verify(1, postRequestedFor(urlEqualTo("/v2/invoicing/invoices"))
			.withRequestBody(matchingJsonPath("$.items[0].unit_amount.value", equalTo("10.80")))
			.withRequestBody(matchingJsonPath("$.primary_recipients[0].billing_info.email_address",
					equalTo("buyer@example.com"))));
		mvc.perform(MockMvcRequestBuilders.get("/api/invoices")
			.with(user("roaster")))
			.andExpect(jsonPath("$[?(@.total.amount == '540.00')].source").value("NEGOTIATION"));

		// The buyer already knows SENT from the invoice reply, so the notifier stays quiet until PayPal says PAID.
		notifier.notifyBuyers();
		CAFE.verify(0, postRequestedFor(urlPathMatching("/cafe/agent/messages")));
		PAYPAL.stubFor(get("/v2/invoicing/invoices/" + payPalId).willReturn(okJson(invoice("PAID"))));
		CLOCK.now = CLOCK.now.plusSeconds(60);
		poller.poll();
		notifier.notifyBuyers();
		notifier.notifyBuyers();
		CAFE.verify(1, postRequestedFor(urlPathMatching("/cafe/agent/messages"))
			.withRequestBody(matchingJsonPath("$.type", equalTo("invoice.status")))
			.withRequestBody(matchingJsonPath("$.body.paypalInvoiceId", equalTo(payPalId)))
			.withRequestBody(matchingJsonPath("$.body.status", equalTo("PAID"))));
	}

	@Test
	void anExpiredQuoteIsRefusedAndNoInvoiceIsCreated() throws Exception {
		UUID conversation = UUID.randomUUID();
		String quoteId = JsonPath.read(send(rfq(conversation)), "$.messageId");

		CLOCK.now = CLOCK.now.plus(RfqHandler.QUOTE_VALID_FOR).plusSeconds(1);
		String reply = send(accept(conversation, quoteId, "cafe.test"));

		// Criterion 5.
		assertThat(type(reply)).isEqualTo("reject");
		assertThat(JsonPath.<String>read(reply, "$.body.reason")).startsWith("This quote expired at ");
		PAYPAL.verify(0, postRequestedFor(urlEqualTo("/v2/invoicing/invoices")));
		assertThat(type(send(counter(conversation, quoteId, "11.00")))).isEqualTo("reject");
	}

	private String send(String envelope) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.post("/agent/messages")
			.contentType(MediaType.APPLICATION_JSON)
			.content(envelope)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static String type(String envelope) {
		return JsonPath.read(envelope, "$.type");
	}

	private static String rfq(UUID conversation) {
		return """
				{ "messageId": "%s", "type": "rfq", "from": "cafe.test", "to": "roaster.test", "conversationId": "%s",
				  "sentAt": "2026-11-03T10:00:00Z",
				  "body": { "product": "green coffee beans", "origin": "Brazil", "quantity": "50", "unit": "kg",
				            "deliverBy": "2099-11-07" } }
				""".formatted(UUID.randomUUID(), conversation);
	}

	private static String counter(UUID conversation, String quoteId, String price) {
		return """
				{ "messageId": "%s", "type": "counter", "from": "cafe.test", "to": "roaster.test", "conversationId": "%s",
				  "sentAt": "2026-11-03T10:00:10Z",
				  "body": { "quoteId": "%s", "unitPrice": { "amount": "%s", "currency": "USD" } } }
				""".formatted(UUID.randomUUID(), conversation, quoteId, price);
	}

	private static String accept(UUID conversation, String quoteId, String from) {
		return """
				{ "messageId": "%s", "type": "accept", "from": "%s", "to": "roaster.test", "conversationId": "%s",
				  "sentAt": "2026-11-03T10:00:30Z",
				  "body": { "quoteId": "%s", "billTo": { "name": "Corner Café", "email": "buyer@example.com" } } }
				""".formatted(UUID.randomUUID(), from, conversation, quoteId);
	}

	private String invoice(String status) {
		return """
				{ "id": "%1$s", "status": "%2$s",
				  "detail": { "invoice_number": "0042", "currency_code": "USD",
				              "payment_term": { "term_type": "DUE_ON_DATE_SPECIFIED", "due_date": "2026-11-17" },
				              "metadata": { "recipient_view_url": "https://www.sandbox.paypal.com/invoice/p/#%1$s" } },
				  "amount": { "currency_code": "USD", "value": "540.00" } }
				""".formatted(payPalId, status);
	}

	/** A clock the test moves, so a quote can expire without waiting a day. */
	static final class MutableClock extends Clock {

		volatile Instant now = Instant.now();

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(now, zone);
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
