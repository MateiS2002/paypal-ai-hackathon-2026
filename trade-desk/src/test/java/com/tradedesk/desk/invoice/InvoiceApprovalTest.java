package com.tradedesk.desk.invoice;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceApprovalTest {

	private static final BigDecimal LIMIT = new BigDecimal("500.00");

	@Test
	void messyOrderAboveTheLimitIsHeld() {
		assertThat(InvoiceService.needsApproval(InvoiceSource.MESSY_ORDER, priced("500.01", "USD"), LIMIT)).isTrue();
		assertThat(InvoiceService.needsApproval(InvoiceSource.MESSY_ORDER, priced("500.00", "USD"), LIMIT)).isFalse();
	}

	@Test
	void messyOrderInAnotherCurrencyIsHeldWhateverTheAmount() {
		assertThat(InvoiceService.needsApproval(InvoiceSource.MESSY_ORDER, priced("480.00", "EUR"), LIMIT)).isTrue();
	}

	@Test
	void messyOrderWithNoLimitConfiguredIsHeld() {
		assertThat(InvoiceService.needsApproval(InvoiceSource.MESSY_ORDER, priced("1.00", "USD"), null)).isTrue();
	}

	@ParameterizedTest
	@EnumSource(value = InvoiceSource.class, names = "MESSY_ORDER", mode = EnumSource.Mode.EXCLUDE)
	void otherSourcesAreNeverHeld(InvoiceSource source) {
		assertThat(InvoiceService.needsApproval(source, priced("9999.00", "EUR"), LIMIT)).isFalse();
		assertThat(InvoiceService.needsApproval(source, priced("9999.00", "USD"), null)).isFalse();
	}

	private static InvoiceTotals.Priced priced(String total, String currency) {
		return new InvoiceTotals.Priced(new NewInvoice.Customer("Cafe", "cafe@example.com"), List.of(),
				new BigDecimal(total), currency);
	}

}
