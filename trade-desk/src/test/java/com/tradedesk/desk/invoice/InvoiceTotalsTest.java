package com.tradedesk.desk.invoice;

import java.math.BigDecimal;
import java.util.List;

import com.tradedesk.desk.contract.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTotalsTest {

	private static final NewInvoice.Customer BEAN_THERE = new NewInvoice.Customer("Bean There", "Buyer@Example.com");

	@Test
	void roundsEachLineHalfUpThenSumsTheRoundedLines() {
		// 0.333 kg × 18.00 = 5.994 -> 5.99; 0.335 × 15.00 = 5.025 -> 5.03 (HALF_UP, not banker's 5.02)
		InvoiceTotals.Priced priced = InvoiceTotals.price(invoice(line("Santos natural", "0.333", "18.00"),
				line("Cerrado", "0.335", "15.00")));

		assertThat(priced.lines()).extracting(InvoiceTotals.PricedLine::lineTotal)
			.containsExactly(new BigDecimal("5.99"), new BigDecimal("5.03"));
		assertThat(priced.total()).isEqualTo(new BigDecimal("11.02"));
		assertThat(priced.currency()).isEqualTo("USD");
	}

	@Test
	void totalIsTheSumOfRoundedLinesNotTheRoundedSum() {
		// Unrounded: 3 × 0.333 × 1.00 = 0.999 -> 1.00, but each line is 0.33, so the total is 0.99.
		InvoiceTotals.Priced priced = InvoiceTotals.price(
				invoice(line("a", "0.333", "1.00"), line("b", "0.333", "1.00"), line("c", "0.333", "1.00")));

		assertThat(priced.total()).isEqualTo(new BigDecimal("0.99"));
	}

	@Test
	void normalisesScalesAndCustomer() {
		InvoiceTotals.Priced priced = InvoiceTotals.price(invoice(line("  Beans  ", "2", "12.5")));

		InvoiceTotals.PricedLine line = priced.lines().getFirst();
		assertThat(line.description()).isEqualTo("Beans");
		assertThat(line.quantity()).isEqualTo(new BigDecimal("2.000"));
		assertThat(line.unitPrice()).isEqualTo(new BigDecimal("12.50"));
		assertThat(priced.customer().email()).isEqualTo("buyer@example.com");
	}

	@ParameterizedTest
	@CsvSource({ "0, 10.00", "-1, 10.00", "1, 0.00", "1, -5.00" })
	void rejectsNonPositiveQuantityOrPrice(String quantity, String price) {
		assertThatThrownBy(() -> InvoiceTotals.price(invoice(line("Beans", quantity, price))))
			.isInstanceOf(InvalidInvoiceException.class)
			.hasMessageContaining("more than zero");
	}

	@Test
	void rejectsSubCentPricesAndTooPreciseQuantities() {
		assertThatThrownBy(() -> InvoiceTotals.price(invoice(line("Beans", "1", "1.005"))))
			.isInstanceOf(InvalidInvoiceException.class);
		assertThatThrownBy(() -> InvoiceTotals.price(invoice(line("Beans", "1.0005", "1.00"))))
			.isInstanceOf(InvalidInvoiceException.class);
	}

	@Test
	void rejectsALineThatRoundsToZero() {
		assertThatThrownBy(() -> InvoiceTotals.price(invoice(line("Dust", "0.001", "0.01"))))
			.isInstanceOf(InvalidInvoiceException.class)
			.hasMessageContaining("rounds to zero");
	}

	@Test
	void rejectsMixedOrUnsupportedCurrencies() {
		NewInvoice.Line eur = new NewInvoice.Line("Beans", BigDecimal.ONE, new Money(new BigDecimal("1.00"), "EUR"));
		assertThatThrownBy(() -> InvoiceTotals.price(invoice(line("Beans", "1", "1.00"), eur)))
			.hasMessageContaining("same currency");

		NewInvoice.Line jpy = new NewInvoice.Line("Beans", BigDecimal.ONE, new Money(new BigDecimal("100"), "JPY"));
		assertThatThrownBy(() -> InvoiceTotals.price(invoice(jpy))).hasMessageContaining("not supported");
	}

	@Test
	void rejectsMissingLinesAndBadCustomer() {
		assertThatThrownBy(() -> InvoiceTotals.price(new NewInvoice(BEAN_THERE, List.of())))
			.hasMessageContaining("at least one line");
		assertThatThrownBy(() -> InvoiceTotals
			.price(new NewInvoice(new NewInvoice.Customer("Bean There", "not-an-email"), List.of(line("a", "1", "1")))))
			.hasMessageContaining("email");
		assertThatThrownBy(() -> InvoiceTotals
			.price(new NewInvoice(new NewInvoice.Customer(" ", "a@b.co"), List.of(line("a", "1", "1")))))
			.hasMessageContaining("name");
	}

	@Test
	void convertsToAndFromCents() {
		assertThat(InvoiceTotals.toMinor(new BigDecimal("11.02"))).isEqualTo(1102L);
		assertThat(InvoiceTotals.fromMinor(1102L)).isEqualTo(new BigDecimal("11.02"));
	}

	private static NewInvoice invoice(NewInvoice.Line... lines) {
		return new NewInvoice(BEAN_THERE, List.of(lines));
	}

	private static NewInvoice.Line line(String description, String quantity, String unitPrice) {
		return new NewInvoice.Line(description, new BigDecimal(quantity), new Money(new BigDecimal(unitPrice), "USD"));
	}

}
