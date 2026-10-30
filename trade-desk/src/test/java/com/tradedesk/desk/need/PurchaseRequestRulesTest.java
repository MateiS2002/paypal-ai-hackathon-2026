package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the browser posts is checked again: the parse step is not the only gate. */
class PurchaseRequestRulesTest {

	static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

	static PurchaseRequest valid() {
		return new PurchaseRequest(" green coffee beans ", "Brazil", new BigDecimal("50"), Unit.KG,
				LocalDate.of(2026, 11, 6), Priority.SPEED, null);
	}

	@Test
	void normalisesAValidRequest() {
		PurchaseRequest checked = PurchaseRequestRules.check(valid(), TODAY);

		assertThat(checked.product()).isEqualTo("green coffee beans");
		assertThat(checked.quantity().scale()).isEqualTo(3);
	}

	@Test
	void refusesEachBrokenRule() {
		PurchaseRequest r = valid();
		assertRefused(new PurchaseRequest("", r.origin(), r.quantity(), r.unit(), r.deliverBy(), r.priority(), null));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), BigDecimal.ZERO, r.unit(), r.deliverBy(),
				r.priority(), null));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), new BigDecimal("1.2345"), r.unit(), r.deliverBy(),
				r.priority(), null));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), new BigDecimal("100001"), r.unit(), r.deliverBy(),
				r.priority(), null));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), r.quantity(), null, r.deliverBy(), r.priority(),
				null));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), r.quantity(), r.unit(), TODAY, r.priority(), null));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), r.quantity(), r.unit(), r.deliverBy(), null, null));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), r.quantity(), r.unit(), r.deliverBy(),
				r.priority(), new Money(new BigDecimal("15.00"), "EUR")));
		assertRefused(new PurchaseRequest(r.product(), r.origin(), r.quantity(), r.unit(), r.deliverBy(),
				r.priority(), new Money(new BigDecimal("-1.00"), "USD")));
		assertRefused(new PurchaseRequest(r.product(), "x".repeat(61), r.quantity(), r.unit(), r.deliverBy(),
				r.priority(), null));
	}

	private static void assertRefused(PurchaseRequest request) {
		assertThatThrownBy(() -> PurchaseRequestRules.check(request, TODAY)).isInstanceOf(InvalidNeedException.class);
	}

}
