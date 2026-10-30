package com.tradedesk.desk.buying;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.tradedesk.desk.agent.AgentJson;
import com.tradedesk.desk.agent.InvoiceBody;
import com.tradedesk.desk.agent.RejectBody;
import com.tradedesk.desk.contract.AgentEnvelope;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.invoice.InvoiceStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The café refuses an invoice with a look-alike pay link or a total other than the one it agreed to. */
class InvoiceReplyCheckTest {

	private final AgentJson json = new AgentJson();

	private final AgentEnvelope accept = new AgentEnvelope(UUID.randomUUID(), AgentEnvelope.Type.ACCEPT, "cafe.test",
			"roaster.test", UUID.randomUUID(), Instant.parse("2026-11-03T10:00:30Z"), json.tree(java.util.Map.of()));

	static final Money AGREED = new Money(new BigDecimal("541.50"), "USD");

	private String reply(AgentEnvelope.Type type, Object body) {
		return json.write(new AgentEnvelope(UUID.randomUUID(), type, "roaster.test", "cafe.test",
				accept.conversationId(), Instant.parse("2026-11-03T10:00:33Z"), json.tree(body)));
	}

	private InvoiceReplyCheck.Outcome check(String url, String total) {
		return InvoiceReplyCheck.check(json, reply(AgentEnvelope.Type.INVOICE, new InvoiceBody("INV2-ABCD-1234",
				url, new Money(new BigDecimal(total), "USD"), InvoiceStatus.SENT)), accept, "roaster.test", AGREED);
	}

	@Test
	void aSandboxInvoiceForTheAgreedTotalIsRecorded() {
		assertThat(check("https://www.sandbox.paypal.com/invoice/p/#INV2-ABCD-1234", "541.50"))
			.isInstanceOf(InvoiceReplyCheck.Invoiced.class);
	}

	@Test
	void aLookAlikePayLinkIsRefused() {
		for (String url : new String[] { "https://www.sandbox.paypal.com.evil.test/invoice/p/",
				"http://www.sandbox.paypal.com/invoice/p/", "https://www.paypal.com/invoice/p/",
				"https://user@www.sandbox.paypal.com/invoice/p/", "https://www.sandbox.paypal.com:8443/invoice/p/",
				"javascript:alert(1)" }) {
			assertThat(check(url, "541.50")).as(url).isInstanceOf(InvoiceReplyCheck.Dropped.class);
		}
	}

	@Test
	void aPayLinkForAnotherInvoiceIsRefused() {
		for (String url : new String[] { "https://www.sandbox.paypal.com/invoice/p/#INV2-ZZZZ-9999",
				"https://www.sandbox.paypal.com/invoice/p/#INV2-ABCD-12345", "https://www.sandbox.paypal.com/invoice/p/" }) {
			assertThat(check(url, "541.50")).as(url).isInstanceOf(InvoiceReplyCheck.Dropped.class);
		}
		assertThat(check("https://www.sandbox.paypal.com/invoice/payerView/details/INV2-ABCD-1234", "541.50"))
			.isInstanceOf(InvoiceReplyCheck.Invoiced.class);
	}

	@Test
	void anotherTotalIsRefused() {
		assertThat(check("https://www.sandbox.paypal.com/invoice/p/#INV2-ABCD-1234", "541.51"))
			.isInstanceOf(InvoiceReplyCheck.Dropped.class);
	}

	@Test
	void aRejectCarriesTheSuppliersReason() {
		InvoiceReplyCheck.Outcome outcome = InvoiceReplyCheck.check(json, reply(AgentEnvelope.Type.REJECT,
				new RejectBody(accept.messageId(), "This quote expired.")), accept, "roaster.test", AGREED);

		assertThat(outcome).isInstanceOfSatisfying(InvoiceReplyCheck.Refused.class,
				refused -> assertThat(refused.reason()).isEqualTo("This quote expired."));
	}

	@Test
	void aReplyFromAnotherDeskIsRefused() {
		assertThat(InvoiceReplyCheck.check(json, reply(AgentEnvelope.Type.INVOICE, null), accept, "supplier-b.test",
				AGREED)).isInstanceOf(InvoiceReplyCheck.Dropped.class);
	}

}
