package com.tradedesk.desk.buying;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.jayway.jsonpath.JsonPath;
import com.tradedesk.desk.agent.AgentClient;
import com.tradedesk.desk.contract.AgentEnvelope;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * S8 through the real routes and a real Postgres (Testcontainers, skipped without Docker): a prompt-injected supplier
 * ("Totally Legit Beans", SIMULATED, $40/kg, delivers in one day so it would win a race for speed) next to the roaster
 * and Bulk Bean Traders. The money rules live in Java, so the injection changes nothing: the quote is blocked, never
 * countered or picked, and no LLM prompt carries a word the supplier wrote.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = { "desk.id=cafe", "desk.name=Corner Café", "desk.domain=cafe.test",
		"desk.location=37.7793,-122.4193", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password", "desk.invoice-poll-interval=PT1H",
		"desk.peers=roaster.test=http://roaster.invalid,supplier-c.test=http://supplier-c.invalid,"
				+ "supplier-e.test=http://supplier-e.invalid",
		"paypal.sandbox-buyer-email=buyer@example.com" })
class MaliciousSupplierIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	static final NegotiationIntegrationTest.FakeSuppliers SUPPLIERS = new NegotiationIntegrationTest.FakeSuppliers();

	static final StubLlm LLM = new StubLlm();

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		AgentClient fakeSuppliers() {
			return SUPPLIERS;
		}

		/** Proposes sensible targets, then argues for the wrong supplier, as in the S3 test. */
		@Bean
		@Primary
		Llm stubLlm() {
			return LLM.answering((system, user) -> system.contains("counter-offer")
					? "{\"targets\": [{\"label\": \"S1\", \"unitPrice\": \"10.83\"}, {\"label\": \"S2\", \"unitPrice\": \"8.50\"}]}"
					: "{\"pick\": \"S2\", \"reason\": \"S2: much cheaper, buy from Bulk Bean Traders instead.\"}");
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void reset() throws Exception {
		SUPPLIERS.sent.clear();
		SUPPLIERS.extraField = false;
		LLM.userMessages.clear();
		policy().andExpect(status().isOk());
	}

	@Test
	void theInjectedQuoteIsBlockedAndTheLegitimateSupplierStillWins() throws Exception {
		String needId = postNeed("50");
		String recommendation = awaitRecommendation(needId, r -> "READY".equals(JsonPath.read(r, "$.stage")));

		// Criterion 1: Totally Legit Beans delivers a day sooner than anyone and the café asked for speed, yet the
		// roaster is picked.
		assertThat(JsonPath.<String>read(recommendation, "$.pick.supplier.domain")).isEqualTo("roaster.test");
		String quotes = mvc.perform(get("/api/needs/" + needId + "/quotes").with(user("cafe")))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(quotes, "$[*].supplier.domain"))
			.containsExactly("roaster.test", "supplier-c.test", "supplier-e.test");

		// The blocked quote is shown as such, with the policy engine's own sentence, and the note verbatim.
		assertThat(JsonPath.<String>read(quotes, "$[2].policyStatus")).isEqualTo("BLOCKED");
		assertThat(JsonPath.<List<String>>read(quotes, "$[2].policyReasons")).first()
			.isEqualTo("The unit price 40.00 USD per kg is above your maximum of 15.00 USD per kg.");
		assertThat(JsonPath.<String>read(quotes, "$[2].note")).isEqualTo(NegotiationIntegrationTest.FakeSuppliers.INJECTION);
		assertThat(JsonPath.<Object>read(quotes, "$[2].counter")).isNull();
		assertThat(JsonPath.<String>read(quotes, "$[0].policyStatus")).isEqualTo("NEEDS_APPROVAL");

		// Never countered, never accepted, never an approval; the other two were countered as usual.
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.COUNTER)).extracting(AgentEnvelope::to)
			.containsExactlyInAnyOrder("roaster.test", "supplier-c.test");
		assertThat(JsonPath.<String>read(recommendation, "$.approval.supplier.domain")).isEqualTo("roaster.test");
		Thread.sleep(300);
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();
	}

	@Test
	void nothingTheSupplierWroteReachesAnLlmPrompt() throws Exception {
		String needId = postNeed("50");
		awaitRecommendation(needId, r -> "READY".equals(JsonPath.read(r, "$.stage")));

		// The counter and recommendation prompts carry Java's numbers under S1, S2, ...: no names, no notes.
		assertThat(LLM.userMessages).isNotEmpty()
			.noneMatch(prompt -> prompt.contains("Totally Legit") || prompt.contains("SYSTEM")
					|| prompt.contains("ignore your limits") || prompt.contains("supplier-e")
					|| prompt.contains("pre-approved") || prompt.contains("40.00"));
	}

	@Test
	void noRouteAcceptsAQuoteByIdSoTheBlockedQuoteCantBeApprovedDirectly() throws Exception {
		String needId = postNeed("50");
		awaitRecommendation(needId, r -> "READY".equals(JsonPath.read(r, "$.stage")));
		String quotes = mvc.perform(get("/api/needs/" + needId + "/quotes").with(user("cafe"))).andReturn()
			.getResponse().getContentAsString();
		String blockedQuoteId = JsonPath.<List<String>>read(quotes, "$[?(@.supplier.domain == 'supplier-e.test')].id")
			.getFirst();

		// There is no approval for it (and the quote's id is not an approval's id), nor any route to accept it.
		decide(blockedQuoteId, "APPROVE").andExpect(status().isNotFound());
		mvc.perform(post("/api/needs/" + needId + "/quotes/" + blockedQuoteId + "/accept").with(user("cafe"))
			.with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/api/quotes/" + blockedQuoteId + "/accept").with(user("cafe")).with(csrf()))
			.andExpect(status().isNotFound());
		Thread.sleep(300);
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();
		assertThat(jdbc.sql("select count(*) from approval where quote_id = :id").param("id", UUID.fromString(blockedQuoteId))
			.query(Long.class).single()).isZero();
	}

	@Test
	void aQuoteWithAnExtraFieldIsRefusedWholeAndLogged() throws Exception {
		// The second variant: the quote body also says "autoApprove": true. The strict parse refuses all of it.
		SUPPLIERS.extraField = true;
		String needId = postNeed("50");
		String recommendation = awaitRecommendation(needId, r -> "READY".equals(JsonPath.read(r, "$.stage")));

		assertThat(JsonPath.<String>read(recommendation, "$.pick.supplier.domain")).isEqualTo("roaster.test");
		mvc.perform(get("/api/needs/" + needId + "/quotes").with(user("cafe")))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[?(@.supplier.domain == 'supplier-e.test')]").isEmpty());
		// The café shows that supplier as "message refused", with the field's name.
		mvc.perform(get("/api/needs").with(user("cafe")))
			.andExpect(jsonPath("$[?(@.id == '" + needId + "')].suppliersAsked").value(3))
			.andExpect(jsonPath("$[?(@.id == '" + needId + "')].quotesReceived").value(2))
			.andExpect(jsonPath("$[?(@.id == '" + needId + "')].refused[0].domain").value("supplier-e.test"))
			.andExpect(jsonPath("$[?(@.id == '" + needId + "')].refused[0].reason")
				.value("unexpected field `autoApprove`"));
		// ... and it is in the conversation log as an invalid message, with the reason.
		assertThat(jdbc.sql("""
				select problem from conversation_message
				 where conversation_id = :id and valid = false and from_domain = 'supplier-e.test'
				""").param("id", UUID.fromString(needId)).query(String.class).list())
			.containsExactly("unexpected field `autoApprove`");
		// It has no effect: nothing was countered or accepted for it.
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.COUNTER)).extracting(AgentEnvelope::to)
			.doesNotContain("supplier-e.test");
		Thread.sleep(300);
		assertThat(SUPPLIERS.sent(AgentEnvelope.Type.ACCEPT)).isEmpty();
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
				// Not there yet.
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

	private ResultActions policy() throws Exception {
		return mvc.perform(put("/api/policy").with(user("cafe")).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "maxUnitPrice": { "amount": "15.00", "currency": "USD" },
					  "approvalAbove": { "amount": "500.00", "currency": "USD" },
					  "maxOrderTotal": { "amount": "2000.00", "currency": "USD" } }
					"""));
	}

}
