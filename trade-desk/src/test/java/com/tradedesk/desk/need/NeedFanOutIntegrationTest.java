package com.tradedesk.desk.need;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.tradedesk.desk.agent.ConversationLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The café's half of S2 through the real routes and a real Postgres (Testcontainers, skipped without Docker), with
 * four supplier desks stubbed by WireMock: two good quotes, one malformed, one reject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = { "desk.id=cafe", "desk.name=Corner Café", "desk.domain=cafe.test",
		"desk.location=37.7793,-122.4193", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password", "desk.invoice-poll-interval=PT1H" })
class NeedFanOutIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	static final WireMockServer PEERS = new WireMockServer(
			WireMockConfiguration.options().dynamicPort().globalTemplating(true));

	static {
		PEERS.start();
	}

	@DynamicPropertySource
	static void peers(DynamicPropertyRegistry registry) {
		String base = PEERS.baseUrl();
		registry.add("desk.peers", () -> String.join(",", "roaster.test=" + base + "/roaster",
				"supplier-b.test=" + base + "/fast", "supplier-c.test=" + base + "/broken",
				"supplier-d.test=" + base + "/picky"));
	}

	@AfterAll
	static void stop() {
		PEERS.stop();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ConversationLog conversations;

	/** A quote that echoes the RFQ's ids back, as a real desk would. */
	private static String quote(String from, String price, String quantity, String deliveryDate) {
		return """
				{ "messageId": "{{randomValue type='UUID'}}", "type": "quote", "from": "%s", "to": "cafe.test",
				  "conversationId": "{{jsonPath request.body '$.conversationId'}}", "sentAt": "2026-11-02T18:00:01Z",
				  "body": { "rfqId": "{{jsonPath request.body '$.messageId'}}", "supplierName": "%s",
				            "simulated": %s, "unitPrice": { "amount": "%s", "currency": "USD" },
				            "quantity": "%s", "availableQuantity": "320", "unit": "kg", "deliveryDate": "%s",
				            "validUntil": "2099-01-01T00:00:00Z", "note": "Fresh lot." } }
				""".formatted(from, from, !from.startsWith("roaster"), price, quantity, deliveryDate);
	}

	@BeforeEach
	void stubPeers() {
		PEERS.resetAll();
		PEERS.stubFor(post(urlEqualTo("/roaster/agent/messages"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json")
				.withBody(quote("roaster.test", "11.40", "50", "2099-01-03"))));
		PEERS.stubFor(post(urlEqualTo("/fast/agent/messages"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json")
				.withFixedDelay(300)
				.withBody(quote("supplier-b.test", "13.49", "50", "2099-01-02"))));
		// Malformed: a negative price. Must be dropped and logged, never shown.
		PEERS.stubFor(post(urlEqualTo("/broken/agent/messages"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json")
				.withBody(quote("supplier-c.test", "-8.93", "50", "2099-01-10"))));
		PEERS.stubFor(post(urlEqualTo("/picky/agent/messages"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
					{ "messageId": "{{randomValue type='UUID'}}", "type": "reject", "from": "supplier-d.test",
					  "to": "cafe.test", "conversationId": "{{jsonPath request.body '$.conversationId'}}",
					  "sentAt": "2026-11-02T18:00:01Z",
					  "body": { "rfqId": "{{jsonPath request.body '$.messageId'}}", "reason": "Out of stock." } }
					""")));
	}

	private static String need(String priority) {
		return """
				{ "product": "green coffee beans", "origin": "Brazil", "quantity": "50", "unit": "kg",
				  "deliverBy": "2099-01-07", "priority": "%s",
				  "maxUnitPrice": { "amount": "15.00", "currency": "USD" } }
				""".formatted(priority);
	}

	private MockHttpServletRequestBuilder postNeed(UUID key, String body) {
		return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/needs")
			.with(user("cafe"))
			.with(csrf())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private String awaitComplete(String needId) throws Exception {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
		while (Instant.now().isBefore(deadline)) {
			String needs = mvc.perform(get("/api/needs").with(user("cafe"))).andReturn().getResponse()
				.getContentAsString();
			List<String> status = JsonPath.read(needs, "$[?(@.id == '" + needId + "')].status");
			if (status.contains("COMPLETE")) {
				return needs;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("need " + needId + " did not complete");
	}

	@Test
	void fansOutKeepsValidQuotesRankedAndDropsTheMalformedOne() throws Exception {
		String created = mvc.perform(postNeed(UUID.randomUUID(), need("SPEED")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("COLLECTING"))
			.andExpect(jsonPath("$.suppliersAsked").value(4))
			.andReturn().getResponse().getContentAsString();
		String needId = JsonPath.read(created, "$.id");

		String needs = awaitComplete(needId);

		assertThat((List<Integer>) JsonPath.read(needs, "$[?(@.id == '" + needId + "')].quotesReceived"))
			.containsExactly(2);
		mvc.perform(get("/api/needs/" + needId + "/quotes").with(user("cafe")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			// SPEED: supplier-b delivers a day sooner than the roaster.
			.andExpect(jsonPath("$[0].supplier.domain").value("supplier-b.test"))
			.andExpect(jsonPath("$[0].rank").value(1))
			.andExpect(jsonPath("$[0].supplier.simulated").value(true))
			.andExpect(jsonPath("$[0].total.amount").value("674.50"))
			.andExpect(jsonPath("$[1].supplier.domain").value("roaster.test"))
			.andExpect(jsonPath("$[1].partial").value(false));

		// Every envelope is stored: 4 RFQs sent, 3 replies read, 1 malformed reply refused with its reason.
		List<ConversationLog.Entry> log = conversations.conversation(UUID.fromString(needId));
		assertThat(log).filteredOn(e -> e.direction().equals("SENT")).hasSize(4)
			.allSatisfy(e -> assertThat(e.type()).isEqualTo("rfq"));
		assertThat(log).filteredOn(e -> e.direction().equals("RECEIVED")).hasSize(4);
		assertThat(log).filteredOn(e -> !e.valid())
			.singleElement()
			.satisfies(e -> assertThat(e.from()).isEqualTo("supplier-c.test"))
			.satisfies(e -> assertThat(e.problem()).contains("unitPrice"));
	}

	@Test
	void theRfqCarriesNoBudgetStockOrPriority() throws Exception {
		String created = mvc.perform(postNeed(UUID.randomUUID(), need("PRICE"))).andReturn().getResponse()
			.getContentAsString();
		awaitComplete(JsonPath.read(created, "$.id"));

		List<LoggedRequest> rfqs = PEERS.findAll(postRequestedFor(urlPathMatching("/.*/agent/messages")));
		assertThat(rfqs).hasSize(4).allSatisfy(request -> {
			String body = request.getBodyAsString();
			assertThat(JsonPath.<java.util.Map<String, Object>>read(body, "$.body")).containsOnlyKeys("product",
					"origin", "quantity", "unit", "deliverBy");
			assertThat(body).doesNotContain("15.00").doesNotContain("PRICE").doesNotContainIgnoringCase("stock");
		});
	}

	@Test
	void aRetrySendsNoSecondRoundAndAReusedKeyIsRefused() throws Exception {
		UUID key = UUID.randomUUID();
		String created = mvc.perform(postNeed(key, need("BALANCED"))).andExpect(status().isCreated()).andReturn()
			.getResponse().getContentAsString();
		awaitComplete(JsonPath.read(created, "$.id"));

		mvc.perform(postNeed(key, need("BALANCED")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value((String) JsonPath.read(created, "$.id")));
		mvc.perform(postNeed(key, need("SPEED"))).andExpect(status().isConflict());

		assertThat(PEERS.findAll(postRequestedFor(urlPathMatching("/.*/agent/messages")))).hasSize(4);
	}

	@Test
	void refusesABadRequestBeforeSendingAnything() throws Exception {
		mvc.perform(postNeed(UUID.randomUUID(), need("SPEED").replace("\"50\"", "\"0\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("quantity")));
		mvc.perform(get("/api/needs/" + UUID.randomUUID() + "/quotes").with(user("cafe")))
			.andExpect(status().isNotFound());

		assertThat(PEERS.findAll(postRequestedFor(urlPathMatching("/.*/agent/messages")))).isEmpty();
	}

}
