package com.tradedesk.desk.supplier;

import java.util.UUID;

import com.tradedesk.desk.agent.ConversationLog;
import com.tradedesk.desk.llm.Llm;
import com.tradedesk.desk.llm.StubLlm;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seller side of S2 through the real {@code POST /agent/messages}, a real Postgres (Testcontainers, skipped
 * without Docker) and a stubbed LLM: seeded catalogue, quote in the response, both envelopes stored, retries answered
 * from storage, bad messages refused and still stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = { "desk.id=roaster", "desk.name=Roaster & Co", "desk.domain=roaster.test",
		"desk.location=37.8044,-122.2712", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password", "desk.persona=roaster", "desk.invoice-poll-interval=PT1H" })
class SupplierAgentIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	@TestConfiguration
	static class StubbedLlm {

		@Bean
		@Primary
		Llm stubLlm() {
			return new StubLlm().answering("Fresh Santos lot, ships in two days.");
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ConversationLog conversations;

	private static String rfq(UUID messageId, UUID conversationId, String to, String type) {
		return """
				{ "messageId": "%s", "type": "%s", "from": "cafe.test", "to": "%s", "conversationId": "%s",
				  "sentAt": "2026-11-03T10:00:00Z",
				  "body": { "product": "green coffee beans", "origin": "Brazil", "quantity": "50", "unit": "kg",
				            "deliverBy": "2099-11-07" } }
				""".formatted(messageId, type, to, conversationId);
	}

	private MvcResult send(String body, int expectedStatus) throws Exception {
		return mvc.perform(post("/agent/messages").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(expectedStatus))
			.andReturn();
	}

	@Test
	void answersAnRfqWithAQuoteAndStoresBothEnvelopes() throws Exception {
		UUID messageId = UUID.randomUUID();
		UUID conversationId = UUID.randomUUID();

		mvc.perform(post("/agent/messages").contentType(MediaType.APPLICATION_JSON)
			.content(rfq(messageId, conversationId, "roaster.test", "rfq")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("quote"))
			.andExpect(jsonPath("$.from").value("roaster.test"))
			.andExpect(jsonPath("$.to").value("cafe.test"))
			.andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
			.andExpect(jsonPath("$.body.rfqId").value(messageId.toString()))
			.andExpect(jsonPath("$.body.unitPrice.amount").value("11.40"))
			.andExpect(jsonPath("$.body.unitPrice.currency").value("USD"))
			.andExpect(jsonPath("$.body.quantity").value("50.000"))
			.andExpect(jsonPath("$.body.availableQuantity").value("320.000"))
			.andExpect(jsonPath("$.body.simulated").value(false))
			.andExpect(jsonPath("$.body.note").value("Fresh Santos lot, ships in two days."));

		assertThat(conversations.conversation(conversationId))
			.extracting(ConversationLog.Entry::direction, ConversationLog.Entry::type, ConversationLog.Entry::valid)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("RECEIVED", "rfq", true),
					org.assertj.core.groups.Tuple.tuple("SENT", "quote", true));
	}

	@Test
	void aRepeatedRfqGetsTheStoredReply() throws Exception {
		UUID messageId = UUID.randomUUID();
		UUID conversationId = UUID.randomUUID();
		String body = rfq(messageId, conversationId, "roaster.test", "rfq");

		String first = send(body, 200).getResponse().getContentAsString();
		String second = send(body, 200).getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(conversations.conversation(conversationId)).hasSize(2);
	}

	@Test
	void refusesMessagesForAnotherDeskOrOfAReplyTypeAndStoresThem() throws Exception {
		UUID conversationId = UUID.randomUUID();

		send(rfq(UUID.randomUUID(), conversationId, "supplier-b.test", "rfq"), 400);
		// A quote is a reply, never a request (S3 accepts counter, accept and invoice.status as requests).
		send(rfq(UUID.randomUUID(), conversationId, "roaster.test", "quote"), 400);

		assertThat(conversations.conversation(conversationId)).hasSize(2)
			.allSatisfy(entry -> assertThat(entry.valid()).isFalse());
	}

	@Test
	void refusesGarbageWithAProblemBody() throws Exception {
		mvc.perform(post("/agent/messages").contentType(MediaType.APPLICATION_JSON).content("{\"hello\": 1}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("The body is not a valid agent envelope."));
	}

	@Test
	void refusesCrossSiteBrowserRequestsBeforeReadingThem() throws Exception {
		UUID conversationId = UUID.randomUUID();
		String body = rfq(UUID.randomUUID(), conversationId, "roaster.test", "rfq");

		mvc.perform(post("/agent/messages").contentType(MediaType.APPLICATION_JSON).content(body)
			.header("Origin", "https://evil.example"))
			.andExpect(status().isForbidden());
		mvc.perform(post("/agent/messages").contentType(MediaType.APPLICATION_JSON).content(body)
			.header("Sec-Fetch-Site", "cross-site"))
			.andExpect(status().isForbidden());

		// Refused before the inbox: nothing was stored or quoted.
		assertThat(conversations.conversation(conversationId)).isEmpty();
	}

	@Test
	void needsNoSessionOrCsrfButTheUiStillDoes() throws Exception {
		send(rfq(UUID.randomUUID(), UUID.randomUUID(), "roaster.test", "rfq"), 200);
		mvc.perform(post("/api/needs/parse").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}"))
			.andExpect(status().is4xxClientError());
	}

}
