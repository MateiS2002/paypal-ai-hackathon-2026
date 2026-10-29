package com.tradedesk.desk.invoice;

import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.tradedesk.desk.paypal.WireMockPayPal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole S1 money path through the real route, a real Postgres (Testcontainers, skipped without Docker) and
 * PayPal stubbed by WireMock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(WireMockPayPal.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = { "desk.id=roaster", "desk.name=Roaster & Co", "desk.domain=roaster.test",
		"desk.location=37.8044,-122.2712", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password", "desk.invoice-approval-above=500.00",
		// The test calls the poller itself.
		"desk.invoice-poll-interval=PT1H", "paypal.client-id=test-client-id",
		"paypal.client-secret=" + InvoiceFlowIntegrationTest.SECRET,
		"paypal.sandbox-buyer-email=buyer@example.com" })
class InvoiceFlowIntegrationTest {

	static final String SECRET = "s3cr3t-never-logged";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	private static final WireMockServer PAYPAL = WireMockPayPal.SERVER;

	/** Fresh per test: invoices share one database, and paypal_invoice_id is unique. */
	private String payPalId;

	// 0.333 × 18.00 = 5.994 -> 5.99, 0.335 × 15.00 = 5.025 -> 5.03, total 11.02.
	private static final String TWO_LINES = """
			{ "customer": { "name": "Bean There", "email": "Buyer@Example.com" },
			  "lines": [
			    { "description": "Santos natural, kg", "quantity": "0.333",
			      "unitPrice": { "amount": "18.00", "currency": "USD" } },
			    { "description": "Cerrado, kg", "quantity": "0.335",
			      "unitPrice": { "amount": "15.00", "currency": "USD" } } ] }
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	InvoicePoller poller;

	@BeforeEach
	void stubPayPal() {
		payPalId = "INV2-" + UUID.randomUUID();
		PAYPAL.resetAll();
		PAYPAL.stubFor(post("/v1/oauth2/token")
			.willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
		PAYPAL.stubFor(post("/v2/invoicing/generate-next-invoice-number")
			.willReturn(okJson("{\"invoice_number\":\"0042\"}")));
		PAYPAL.stubFor(post("/v2/invoicing/invoices").willReturn(okJson(invoice("DRAFT", "11.02")).withStatus(201)));
		PAYPAL.stubFor(post(urlPathMatching("/v2/invoicing/invoices/" + payPalId + "/send")).willReturn(okJson(
				"{\"href\":\"https://www.sandbox.paypal.com/invoice/p/#" + payPalId + "\",\"rel\":\"payer-view\",\"method\":\"GET\"}")));
		PAYPAL.stubFor(get("/v2/invoicing/invoices/" + payPalId).willReturn(okJson(invoice("SENT", "11.02"))));
	}

	@Test
	void createsAndSendsATwoLineInvoiceWithJavaTotals() throws Exception {
		create(UUID.randomUUID(), TWO_LINES).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SENT"))
			.andExpect(jsonPath("$.invoiceNumber").value("0042"))
			.andExpect(jsonPath("$.total.amount").value("11.02"))
			.andExpect(jsonPath("$.lines[0].quantity").value("0.333"))
			.andExpect(jsonPath("$.lines[0].lineTotal.amount").value("5.99"))
			.andExpect(jsonPath("$.lines[1].lineTotal.amount").value("5.03"))
			.andExpect(jsonPath("$.customer.email").value("buyer@example.com"))
			.andExpect(jsonPath("$.source").value("MANUAL"))
			.andExpect(jsonPath("$.payerViewUrl").value("https://www.sandbox.paypal.com/invoice/p/#" + payPalId));

		// PayPal gets the same rounded quantity and unit price per line, an explicit due date and an idempotency id.
		PAYPAL.verify(1, postRequestedFor(urlEqualTo("/v2/invoicing/invoices"))
			.withHeader("Authorization", equalTo("Bearer test-token"))
			.withHeader("PayPal-Request-Id", matching("[0-9a-f-]{36}"))
			.withRequestBody(matchingJsonPath("$.items[0].quantity", equalTo("0.333")))
			.withRequestBody(matchingJsonPath("$.items[0].unit_amount.value", equalTo("18.00")))
			.withRequestBody(matchingJsonPath("$.detail.invoice_number", equalTo("0042")))
			.withRequestBody(matchingJsonPath("$.detail.payment_term.term_type", equalTo("DUE_ON_DATE_SPECIFIED")))
			.withRequestBody(matchingJsonPath("$.detail.payment_term.due_date", matching("\\d{4}-\\d{2}-\\d{2}")))
			.withRequestBody(matchingJsonPath("$.primary_recipients[0].billing_info.email_address",
					equalTo("buyer@example.com"))));
		PAYPAL.verify(1, postRequestedFor(urlEqualTo("/v2/invoicing/invoices/" + payPalId + "/send")));
	}

	@Test
	void sameKeyTwiceMakesOnePayPalInvoice() throws Exception {
		UUID key = UUID.randomUUID();
		String id = idOf(create(key, TWO_LINES).andExpect(status().isCreated()));

		create(key, TWO_LINES).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

		PAYPAL.verify(1, postRequestedFor(urlEqualTo("/v2/invoicing/invoices")));
		PAYPAL.verify(1, postRequestedFor(urlEqualTo("/v2/invoicing/invoices/" + payPalId + "/send")));
	}

	@Test
	void sameKeyWithADifferentBodyIsAConflict() throws Exception {
		UUID key = UUID.randomUUID();
		create(key, TWO_LINES).andExpect(status().isCreated());

		create(key, TWO_LINES.replace("0.333", "1.000")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));
	}

	@Test
	void aFailedSendResumesWithTheSendOnly() throws Exception {
		// The first send fails, as if the desk crashed between create and send.
		PAYPAL.stubFor(post("/v2/invoicing/invoices/" + payPalId + "/send").inScenario("send")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("recovered"));
		PAYPAL.stubFor(post("/v2/invoicing/invoices/" + payPalId + "/send").inScenario("send")
			.whenScenarioStateIs("recovered")
			.willReturn(okJson("{\"href\":\"https://www.sandbox.paypal.com/invoice/p/#" + payPalId + "\",\"rel\":\"payer-view\"}")));
		PAYPAL.stubFor(get("/v2/invoicing/invoices/" + payPalId).willReturn(okJson(invoice("DRAFT", "11.02"))));
		UUID key = UUID.randomUUID();

		create(key, TWO_LINES).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.detail").value(containsString("Try again")));
		mvc.perform(MockMvcRequestBuilders.get("/api/invoices")
			.with(user("roaster"))).andExpect(jsonPath("$[0].status").value("DRAFT"));

		create(key, TWO_LINES).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));

		PAYPAL.verify(1, postRequestedFor(urlEqualTo("/v2/invoicing/invoices")));
		PAYPAL.verify(2, postRequestedFor(urlEqualTo("/v2/invoicing/invoices/" + payPalId + "/send")));
	}

	@Test
	void nonPositiveQuantityIsRejectedBeforeAnyPayPalCall() throws Exception {
		create(UUID.randomUUID(), TWO_LINES.replace("0.333", "0")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Line 1: the quantity must be more than zero."));
		create(UUID.randomUUID(), TWO_LINES.replace("18.00", "-1.00")).andExpect(status().isBadRequest());

		PAYPAL.verify(0, postRequestedFor(anyUrl()));
	}

	@Test
	void missingIdempotencyKeyIsRejected() throws Exception {
		mvc.perform(MockMvcRequestBuilders.post("/api/invoices")
			.with(user("roaster"))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(TWO_LINES))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("The Idempotency-Key header is required."));
	}

	@Test
	void wrongCredentialsGiveAReadableErrorAndLogNoSecret(CapturedOutput output) throws Exception {
		PAYPAL.stubFor(post("/v1/oauth2/token").willReturn(aResponse().withStatus(401)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\":\"invalid_client\",\"error_description\":\"Client Authentication failed\"}")));
		// A token cached by an earlier test is refused too, so the client has to ask for a new one.
		PAYPAL.stubFor(post("/v2/invoicing/generate-next-invoice-number").willReturn(aResponse().withStatus(401)));

		String body = create(UUID.randomUUID(), TWO_LINES).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.detail").value(containsString("rejected")))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContain(SECRET).doesNotContain("Exception").doesNotContain("at com.");
		assertThat(output).doesNotContain(SECRET).doesNotContain("test-token");
	}

	@Test
	void payPalTotalThatDiffersIsNotSent() throws Exception {
		PAYPAL.stubFor(post("/v2/invoicing/invoices").willReturn(okJson(invoice("DRAFT", "11.03")).withStatus(201)));

		create(UUID.randomUUID(), TWO_LINES).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.detail").value(containsString("different total")));

		PAYPAL.verify(0, postRequestedFor(urlEqualTo("/v2/invoicing/invoices/" + payPalId + "/send")));
	}

	@Test
	void pollerFlipsTheRowToPaid() throws Exception {
		String id = idOf(create(UUID.randomUUID(), TWO_LINES).andExpect(status().isCreated()));
		PAYPAL.stubFor(get("/v2/invoicing/invoices/" + payPalId).willReturn(okJson(invoice("PAID", "11.02"))));

		poller.poll();

		mvc.perform(MockMvcRequestBuilders.get("/api/invoices")
			.with(user("roaster")))
			.andExpect(jsonPath("$[?(@.id == '" + id + "')].status").value("PAID"))
			.andExpect(jsonPath("$[?(@.id == '" + id + "')].paidAt").isNotEmpty());
	}

	@Test
	void demoCustomerIsSeeded() throws Exception {
		mvc.perform(MockMvcRequestBuilders.get("/api/customers")
			.with(user("roaster")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.email == 'buyer@example.com')].name").value("Bean There"));
	}

	private ResultActions create(UUID key, String body) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.post("/api/invoices")
			.with(user("roaster"))
			.with(csrf())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static String idOf(ResultActions result) throws Exception {
		String json = result.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.id");
	}

	private String invoice(String status, String total) {
		return """
				{ "id": "%3$s", "status": "%s",
				  "detail": { "invoice_number": "0042", "currency_code": "USD",
				              "payment_term": { "term_type": "DUE_ON_DATE_SPECIFIED", "due_date": "2026-11-12" },
				              "metadata": { "recipient_view_url": "https://www.sandbox.paypal.com/invoice/p/#%3$s" } },
				  "amount": { "currency_code": "USD", "value": "%s" } }
				""".formatted(status, total, payPalId);
	}

}
