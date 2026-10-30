package com.tradedesk.desk.buying;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import com.jayway.jsonpath.JsonPath;
import com.tradedesk.desk.agent.AcceptBody;
import com.tradedesk.desk.agent.AgentClient;
import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.CounterBody;
import com.tradedesk.desk.agent.InvoiceBody;
import com.tradedesk.desk.agent.PeerDirectory;
import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.agent.RejectBody;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.invoice.InvoiceStatus;
import com.tradedesk.desk.llm.Llm;
import com.tradedesk.desk.llm.StubLlm;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The café's half of S3 through the real routes and a real Postgres (Testcontainers, skipped without Docker). Two
 * suppliers are played in Java by a fake {@link AgentClient} that records every message the café sends: the roaster
 * meets the counter, Bulk Bean Traders refuses it. The stub LLM always argues for the wrong supplier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = { "desk.id=cafe", "desk.name=Corner Café", "desk.domain=cafe.test",
		"desk.location=37.7793,-122.4193", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password", "desk.invoice-poll-interval=PT1H",
		"desk.peers=roaster.test=http://roaster.invalid,supplier-c.test=http://supplier-c.invalid",
		"paypal.sandbox-buyer-email=buyer@example.com" })
class NegotiationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	static final FakeSuppliers SUPPLIERS = new FakeSuppliers();

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		AgentClient fakeSuppliers() {
			return SUPPLIERS;
		}

		/** Proposes the roaster's 5% target, then argues for the wrong supplier. */
		@Bean
		@Primary
		Llm stubLlm() {
			return new StubLlm().answering((system, user) -> system.contains("counter-offer")
					? "{\"targets\": [{\"label\": \"S1\", \"unitPrice\": \"10.83\"}, {\"label\": \"S2\", \"unitPrice\": \"8.50\"}]}"
					: "{\"pick\": \"S2\", \"reason\": \"S2: much cheaper, buy from Bulk Bean Traders instead.\"}");
		}

	}

	@Autowired
	MockMvc mvc;

	@BeforeEach
	void reset() throws Exception {
		SUPPLIERS.sent.clear();
		policy("15.00", "500.00", "2000.00").andExpect(status().isOk());
	}

	@Test
	void countersRecommendsJavasPickAndSendsNothingUntilTheOwnerApproves() throws Exception {
		String needId = postNeed("50");
		String recommendation = awaitRecommendation(needId, r -> "READY".equals(JsonPath.read(r, "$.stage")));

		// Criterion 6: Java ranked the roaster first for speed; the LLM's pitch for Bulk Bean Traders was discarded.
		assertThat(JsonPath.<String>read(recommendation, "$.pick.supplier.domain")).isEqualTo("roaster.test");
		assertThat(JsonPath.<String>read(recommendation, "$.reasonSource")).isEqualTo("TEMPLATE");
		assertThat(JsonPath.<String>read(recommendation, "$.reason")).startsWith("Roaster & Co: ");
		// The counter round: the roaster met 10.83 (was 11.40); the other refused, so its quote stands.
		assertThat(JsonPath.<String>read(recommendation, "$.pick.unitPrice.amount")).isEqualTo("10.83");
		assertThat(JsonPath.<String>read(recommendation, "$.pick.counter.outcome")).isEqualTo("MET");
		assertThat(JsonPath.<String>read(recommendation, "$.pick.counter.previousUnitPrice.amount"))
			.isEqualTo("11.40");
		mvc.perform(get("/api/needs/" + needId + "/quotes").with(user("cafe")))
			.andExpect(jsonPath("$[1].supplier.domain").value("supplier-c.test"))
			.andExpect(jsonPath("$[1].counter.outcome").value("HELD"))
			.andExpect(jsonPath("$[1].unitPrice.amount").value("8.93"));
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.COUNTER)).hasSize(2);

		// 50 × 10.83 = 541.50, above the 500 limit: the owner must approve.
		assertThat(JsonPath.<String>read(recommendation, "$.approval.decision")).isEqualTo("NEEDS_APPROVAL");
		assertThat(JsonPath.<String>read(recommendation, "$.approval.status")).isEqualTo("PENDING");
		assertThat(JsonPath.<String>read(recommendation, "$.approval.total.amount")).isEqualTo("541.50");
		assertThat(JsonPath.<List<String>>read(recommendation, "$.approval.reasons"))
			.containsExactly("The total 541.50 USD is above your approval limit of 500.00 USD.");

		// Criterion 3: nothing reaches a supplier between "needs approval" and the tap.
		Thread.sleep(500);
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();

		String approvalId = JsonPath.read(recommendation, "$.approval.id");
		decide(approvalId, "APPROVE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
		awaitRecommendation(needId, r -> "INVOICED".equals(JsonPath.read(r, "$.approval.order")));
		decide(approvalId, "APPROVE").andExpect(status().isConflict());

		AgentEnvelope accept = SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT).getFirst();
		assertThat(accept.to()).isEqualTo("roaster.test");
		AcceptBody acceptBody = new AgentJson().body(accept.body(), AcceptBody.class).orElseThrow();
		assertThat(acceptBody.billTo()).isEqualTo(new AcceptBody.BillTo("Corner Café", "buyer@example.com"));

		String payables = mvc.perform(get("/api/payables").with(user("cafe"))).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();
		List<Map<String, Object>> rows = JsonPath.read(payables, "$[?(@.needId == '" + needId + "')]");
		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(JsonPath.<String>read(row, "$.total.amount")).isEqualTo("541.50");
			assertThat(JsonPath.<String>read(row, "$.status")).isEqualTo("SENT");
			assertThat(JsonPath.<String>read(row, "$.description")).isEqualTo("50 kg green coffee beans from Brazil");
			assertThat(JsonPath.<String>read(row, "$.payerViewUrl")).startsWith("https://www.sandbox.paypal.com/");
		});
		String payPalId = JsonPath.read(rows.getFirst(), "$.paypalInvoiceId");

		// invoice.status: only from the invoice's own supplier, and only newer than what the café holds.
		agentStatus("supplier-c.test", needId, payPalId, "PAID", "2099-01-01T00:00:00Z")
			.andExpect(jsonPath("$.type").value("reject"));
		agentStatus("roaster.test", needId, "INV2-UNKNOWN", "PAID", "2099-01-01T00:00:00Z")
			.andExpect(jsonPath("$.type").value("reject"));
		agentStatus("roaster.test", needId, payPalId, "PAID", "2099-01-01T00:00:00Z")
			.andExpect(jsonPath("$.type").value("invoice.status"))
			.andExpect(jsonPath("$.body.status").value("PAID"));
		agentStatus("roaster.test", needId, payPalId, "SENT", "2098-01-01T00:00:00Z")
			.andExpect(jsonPath("$.body.status").value("PAID"));
		// A newer repeat of PAID moves the held time on, so a SENT report in between can't bring the status back.
		agentStatus("roaster.test", needId, payPalId, "PAID", "2099-06-01T00:00:00Z")
			.andExpect(jsonPath("$.body.status").value("PAID"));
		agentStatus("roaster.test", needId, payPalId, "SENT", "2099-03-01T00:00:00Z")
			.andExpect(jsonPath("$.body.status").value("PAID"));
		mvc.perform(get("/api/payables").with(user("cafe")))
			.andExpect(jsonPath("$[?(@.paypalInvoiceId == '" + payPalId + "')].status").value("PAID"));
	}

	@Test
	void anOrderUnderTheLimitAutoAccepts() throws Exception {
		// 46 kg: 46 × 10.83 = 498.18, under 500.
		String needId = postNeed("46");
		String done = awaitRecommendation(needId, r -> "INVOICED".equals(JsonPath.read(r, "$.approval.order")));

		assertThat(JsonPath.<String>read(done, "$.approval.decision")).isEqualTo("AUTO_ACCEPT");
		assertThat(JsonPath.<String>read(done, "$.approval.status")).isEqualTo("AUTO_APPROVED");
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).hasSize(1);
	}

	@Test
	void anUnansweredOrderCanBeSentAgainWithTheSameAccept() throws Exception {
		SUPPLIERS.acceptDown = true;
		try {
			String needId = postNeed("46");
			String failed = awaitRecommendation(needId, r -> "UNANSWERED".equals(JsonPath.read(r, "$.approval.order")));
			assertThat(JsonPath.<String>read(failed, "$.approval.orderDetail")).contains("Try again");
			assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).hasSize(OrderDesk.ACCEPT_TRIES);

			SUPPLIERS.acceptDown = false;
			decide(JsonPath.read(failed, "$.approval.id"), "APPROVE").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AUTO_APPROVED"));
			awaitRecommendation(needId, r -> "INVOICED".equals(JsonPath.read(r, "$.approval.order")));

			// Every try is the same message, so a supplier that already invoiced it returns the stored invoice.
			assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).extracting(AgentEnvelope::messageId)
				.hasSize(OrderDesk.ACCEPT_TRIES + 1)
				.containsOnly(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT).getFirst().messageId());
			decide(JsonPath.read(failed, "$.approval.id"), "APPROVE").andExpect(status().isConflict());
		}
		finally {
			SUPPLIERS.acceptDown = false;
		}
	}

	@Test
	void anUnansweredOrderIsNotSentAgainOnceThePolicyBlocksIt() throws Exception {
		SUPPLIERS.acceptDown = true;
		String approvalId;
		try {
			String needId = postNeed("46");
			String failed = awaitRecommendation(needId, r -> "UNANSWERED".equals(JsonPath.read(r, "$.approval.order")));
			approvalId = JsonPath.read(failed, "$.approval.id");
		}
		finally {
			SUPPLIERS.acceptDown = false;
		}
		policy("10.00", "500.00", "2000.00").andExpect(status().isOk());

		decide(approvalId, "APPROVE").andExpect(status().isConflict());
		Thread.sleep(300);
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).hasSize(OrderDesk.ACCEPT_TRIES);
	}

	@Test
	void aRefusedInvoiceFailsForGoodAndCantBeRetried() throws Exception {
		SUPPLIERS.wrongTotal = true;
		try {
			String needId = postNeed("46");
			String failed = awaitRecommendation(needId, r -> "FAILED".equals(JsonPath.read(r, "$.approval.order")));

			decide(JsonPath.read(failed, "$.approval.id"), "APPROVE").andExpect(status().isConflict());
			assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).hasSize(1);
		}
		finally {
			SUPPLIERS.wrongTotal = false;
		}
	}

	@Test
	void aBlockedOrderIsNeverSentAndCantBeApproved() throws Exception {
		policy("10.00", "500.00", "2000.00").andExpect(status().isOk());

		String needId = postNeed("50");
		String blocked = awaitRecommendation(needId, r -> "READY".equals(JsonPath.read(r, "$.stage")));

		assertThat(JsonPath.<String>read(blocked, "$.approval.decision")).isEqualTo("BLOCKED");
		// The café countered at its 10.00 maximum; the roaster went down to its 10.80 floor, still too dear.
		assertThat(JsonPath.<List<String>>read(blocked, "$.approval.reasons"))
			.contains("The unit price 10.80 USD per kg is above your maximum of 10.00 USD per kg.");
		decide(JsonPath.read(blocked, "$.approval.id"), "APPROVE").andExpect(status().isConflict());
		Thread.sleep(300);
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();
	}

	@Test
	void aQuoteWhoseCounterAnswerWasDroppedIsNeverPicked() throws Exception {
		// The roaster answers the counter with a "revision" dearer than its quote: dropped, and the roaster may have
		// replaced its quote, so Java picks the next one even though the roaster ranks first for speed.
		SUPPLIERS.brokenRevision = true;
		try {
			String needId = postNeed("50");
			String done = awaitRecommendation(needId, r -> "INVOICED".equals(JsonPath.read(r, "$.approval.order")));

			assertThat(JsonPath.<String>read(done, "$.pick.supplier.domain")).isEqualTo("supplier-c.test");
			mvc.perform(get("/api/needs/" + needId + "/quotes").with(user("cafe")))
				.andExpect(jsonPath("$[0].supplier.domain").value("roaster.test"))
				.andExpect(jsonPath("$[0].counter.outcome").value("NO_ANSWER"))
				.andExpect(jsonPath("$[0].unitPrice.amount").value("11.40"));
			// 50 × 8.93 = 446.50: under the limit, so it auto-accepts, and only Bulk Bean Traders gets an accept.
			assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).singleElement()
				.satisfies(accept -> assertThat(accept.to()).isEqualTo("supplier-c.test"));
		}
		finally {
			SUPPLIERS.brokenRevision = false;
		}
	}

	@Test
	void aPartialOfferWaitsForTheOwner() throws Exception {
		// 400 kg: neither supplier fakes more than 320 kg of stock, so both offers are partial.
		SUPPLIERS.stock = new BigDecimal("320");
		// Room for 320 kg × 10.83 = 3465.60, so the order isn't blocked by the maximum.
		policy("15.00", "500.00", "5000.00").andExpect(status().isOk());
		try {
			String needId = postNeed("400");
			String pending = awaitRecommendation(needId,
					r -> "PENDING".equals(JsonPath.read(r, "$.approval.status")));

			assertThat(JsonPath.<List<String>>read(pending, "$.approval.reasons"))
				.contains("The supplier offers 320 kg of the 400 kg you asked for.");
			assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();
		}
		finally {
			SUPPLIERS.stock = null;
		}
	}

	@Test
	void approvingAfterTheLimitsTightenedIsRefusedAndNothingIsSent() throws Exception {
		String needId = postNeed("50");
		String pending = awaitRecommendation(needId, r -> "PENDING".equals(JsonPath.read(r, "$.approval.status")));
		policy("15.00", "500.00", "520.00").andExpect(status().isOk());

		decide(JsonPath.read(pending, "$.approval.id"), "APPROVE")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("maximum order of 520.00")));

		mvc.perform(get("/api/needs/" + needId + "/recommendation").with(user("cafe")))
			.andExpect(jsonPath("$.approval.status").value("BLOCKED"));
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();
	}

	@Test
	void rejectingSendsNothing() throws Exception {
		String needId = postNeed("50");
		String pending = awaitRecommendation(needId, r -> "PENDING".equals(JsonPath.read(r, "$.approval.status")));

		decide(JsonPath.read(pending, "$.approval.id"), "REJECT").andExpect(jsonPath("$.status").value("REJECTED"));

		Thread.sleep(300);
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();
	}

	@Test
	void thePolicyIsCheckedBeforeItIsStored() throws Exception {
		policy("0.00", "500.00", "2000.00").andExpect(status().isBadRequest());
		policy("15.001", "500.00", "2000.00").andExpect(status().isBadRequest());
		mvc.perform(get("/api/policy").with(user("cafe")))
			.andExpect(jsonPath("$.maxUnitPrice.amount").value("15.00"))
			.andExpect(jsonPath("$.approvalAbove.amount").value("500.00"));
		decide(UUID.randomUUID().toString(), "APPROVE").andExpect(status().isNotFound());
	}

	private String postNeed(String quantity) throws Exception {
		String body = """
				{ "product": "green coffee beans", "origin": "Brazil", "quantity": "%s", "unit": "kg",
				  "deliverBy": "2099-01-07", "priority": "SPEED", "maxUnitPrice": null }
				""".formatted(quantity);
		String created = mvc.perform(post("/api/needs").with(user("cafe")).with(csrf())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(created, "$.id");
	}

	private String awaitRecommendation(String needId, Predicate<String> done) throws Exception {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
		String last = null;
		while (Instant.now().isBefore(deadline)) {
			last = mvc.perform(get("/api/needs/" + needId + "/recommendation").with(user("cafe"))).andReturn()
				.getResponse().getContentAsString();
			try {
				if (done.test(last)) {
					return last;
				}
			}
			catch (RuntimeException ex) {
				// Not there yet (for example no approval).
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Recommendation for " + needId + " never got there: " + last);
	}

	private ResultActions decide(String approvalId, String decision) throws Exception {
		return mvc.perform(post("/api/approvals/" + approvalId).with(user("cafe")).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"decision\": \"" + decision + "\"}"));
	}

	private ResultActions policy(String maxUnit, String approvalAbove, String maxTotal) throws Exception {
		return mvc.perform(put("/api/policy").with(user("cafe")).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "maxUnitPrice": { "amount": "%s", "currency": "USD" },
					  "approvalAbove": { "amount": "%s", "currency": "USD" },
					  "maxOrderTotal": { "amount": "%s", "currency": "USD" } }
					""".formatted(maxUnit, approvalAbove, maxTotal)));
	}

	private ResultActions agentStatus(String from, String needId, String payPalId, String status, String updatedAt)
			throws Exception {
		return mvc.perform(post("/agent/messages").contentType(MediaType.APPLICATION_JSON).content("""
				{ "messageId": "%s", "type": "invoice.status", "from": "%s", "to": "cafe.test", "conversationId": "%s",
				  "sentAt": "2026-11-03T10:04:13Z",
				  "body": { "paypalInvoiceId": "%s", "status": "%s", "updatedAt": "%s" } }
				""".formatted(UUID.randomUUID(), from, needId, payPalId, status, updatedAt)))
			.andExpect(status().isOk());
	}

	/**
	 * Two supplier desks in Java. The roaster quotes 11.40 (two days) and meets any counter down to 10.80; Bulk Bean
	 * Traders quotes 8.93 (nine days) and refuses every counter. Accepts get an invoice for the agreed total.
	 */
	static class FakeSuppliers extends AgentClient {

		final List<AgentEnvelope> sent = new CopyOnWriteArrayList<>();

		private final AgentJson json = new AgentJson();

		/** When true, the roaster answers a counter with a revision dearer than its quote, which the café drops. */
		volatile boolean brokenRevision;

		/** When true, every accept gets HTTP 503, as from a supplier desk that is down. */
		volatile boolean acceptDown;

		/** When true, invoices come back one dollar over the agreed total, which the café refuses. */
		volatile boolean wrongTotal;

		/** When set, both suppliers offer at most this much (a partial offer for a larger need). */
		volatile BigDecimal stock;

		/** Every quote handed out, by message id. */
		private final Map<UUID, Quoted> quotes = new ConcurrentHashMap<>();

		record Quoted(UUID rfqId, String name, BigDecimal price, BigDecimal quantity, LocalDate delivery) {
		}

		List<AgentEnvelope> sent(AgentEnvelope.Type type) {
			return sent.stream().filter(e -> e.type() == type).toList();
		}

		@Override
		public Reply send(PeerDirectory.Peer peer, String envelope) {
			return answer(envelope);
		}

		@Override
		public Reply sendSlow(PeerDirectory.Peer peer, String envelope) {
			return answer(envelope);
		}

		private Reply answer(String raw) {
			AgentEnvelope in = json.envelope(raw).orElseThrow();
			sent.add(in);
			if (acceptDown && in.type() == AgentEnvelope.Type.ACCEPT) {
				return new Reply(503, "", null);
			}
			boolean roaster = in.to().equals("roaster.test");
			AgentEnvelope out = switch (in.type()) {
				case RFQ -> {
					BigDecimal requested = new BigDecimal(JsonPath.read(raw, "$.body.quantity").toString());
					BigDecimal quantity = stock == null ? requested : requested.min(stock);
					Quoted quoted = new Quoted(in.messageId(), roaster ? "Roaster & Co" : "Bulk Bean Traders",
							new BigDecimal(roaster ? "11.40" : "8.93"), quantity,
							LocalDate.now().plusDays(roaster ? 2 : 9));
					yield quote(in, quoted, null);
				}
				case COUNTER -> {
					CounterBody counter = json.body(in.body(), CounterBody.class).orElseThrow();
					Quoted old = quotes.get(counter.quoteId());
					if (!roaster) {
						yield reply(in, AgentEnvelope.Type.REJECT, new RejectBody(in.messageId(), "Our price is firm."));
					}
					BigDecimal price = brokenRevision ? new BigDecimal("12.00")
							: counter.unitPrice().amount().max(new BigDecimal("10.80"));
					yield quote(in, new Quoted(old.rfqId(), old.name(), price, old.quantity(), old.delivery()),
							counter.quoteId());
				}
				case ACCEPT -> {
					AcceptBody accept = json.body(in.body(), AcceptBody.class).orElseThrow();
					Quoted quoted = quotes.get(accept.quoteId());
					String id = "INV2-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
					yield reply(in, AgentEnvelope.Type.INVOICE, new InvoiceBody(id,
							"https://www.sandbox.paypal.com/invoice/p/#" + id,
							new Money(quoted.quantity().multiply(quoted.price())
								.add(wrongTotal ? BigDecimal.ONE : BigDecimal.ZERO)
								.setScale(2, RoundingMode.HALF_UP), "USD"),
							InvoiceStatus.SENT));
				}
				default -> throw new IllegalStateException("unexpected " + in.type());
			};
			return new Reply(200, json.write(out), null);
		}

		private AgentEnvelope quote(AgentEnvelope in, Quoted quoted, UUID revises) {
			AgentEnvelope out = reply(in, AgentEnvelope.Type.QUOTE, new QuoteBody(quoted.rfqId(), quoted.name(),
					!quoted.name().startsWith("Roaster"), new Money(quoted.price(), "USD"), quoted.quantity(),
					new BigDecimal("320"), Unit.KG, quoted.delivery(), Instant.now().plus(Duration.ofHours(24)),
					revises == null ? "Fresh lot." : "Revised.", revises));
			quotes.put(out.messageId(), quoted);
			return out;
		}

		private AgentEnvelope reply(AgentEnvelope in, AgentEnvelope.Type type, Object body) {
			return new AgentEnvelope(UUID.randomUUID(), type, in.to(), in.from(), in.conversationId(), Instant.now(),
					json.tree(body));
		}

	}

}
