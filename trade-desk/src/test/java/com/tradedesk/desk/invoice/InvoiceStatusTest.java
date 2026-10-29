package com.tradedesk.desk.invoice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceStatusTest {

	@ParameterizedTest
	@CsvSource({ "DRAFT, DRAFT", "SENT, SENT", "SCHEDULED, SENT", "PAID, PAID", "MARKED_AS_PAID, PAID",
			"PAID_EXTERNAL, PAID", "PARTIALLY_PAID, PARTIALLY_PAID", "UNPAID, UNPAID",
			"PAYMENT_PENDING, PAYMENT_PENDING", "CANCELLED, CANCELLED", "AUTO_CANCELLED, CANCELLED",
			"REFUNDED, REFUNDED", "MARKED_AS_REFUNDED, REFUNDED", "PARTIALLY_REFUNDED, REFUNDED" })
	void mapsPayPalStatuses(String payPal, InvoiceStatus ours) {
		assertThat(InvoiceStatus.fromPayPal(payPal)).isEqualTo(ours);
	}

	@Test
	void unknownOrMissingStatusIsOtherAndDoesNotThrow() {
		assertThat(InvoiceStatus.fromPayPal("SOMETHING_NEW")).isEqualTo(InvoiceStatus.OTHER);
		assertThat(InvoiceStatus.fromPayPal("bad\nlogé😀 line")).isEqualTo(InvoiceStatus.OTHER);
		assertThat(InvoiceStatus.fromPayPal(null)).isEqualTo(InvoiceStatus.OTHER);
		// Our own values are not PayPal's.
		assertThat(InvoiceStatus.fromPayPal("AWAITING_APPROVAL")).isEqualTo(InvoiceStatus.OTHER);
	}

	@Test
	void onlyStatusesPayPalCanStillChangeArePolled() {
		assertThat(InvoiceStatus.SENT.isOpen()).isTrue();
		assertThat(InvoiceStatus.PAYMENT_PENDING.isOpen()).isTrue();
		assertThat(InvoiceStatus.PAID.isOpen()).isFalse();
		assertThat(InvoiceStatus.AWAITING_APPROVAL.isOpen()).isFalse();
		assertThat(InvoiceStatus.DRAFT.isOpen()).isFalse();
	}

}
