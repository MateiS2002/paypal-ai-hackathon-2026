package com.tradedesk.desk.invoice;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.tradedesk.desk.paypal.PayPalClient;
import com.tradedesk.desk.paypal.PayPalException;
import com.tradedesk.desk.paypal.PayPalInvoicing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoicePollerTest {

	private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

	private final InvoiceRepository invoices = mock(InvoiceRepository.class);

	private final InvoiceService service = mock(InvoiceService.class);

	private final PayPalClient payPal = mock(PayPalClient.class);

	private final InvoicePoller poller = new InvoicePoller(invoices, service, payPal,
			Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID broken = UUID.randomUUID();

	private final UUID later = UUID.randomUUID();

	@BeforeEach
	void twoOpenInvoices() {
		when(payPal.configured()).thenReturn(true);
		// Oldest first, as findOpen() orders them: the failing one is polled before the one that got paid.
		when(invoices.findOpen())
			.thenReturn(List.of(new InvoiceRepository.OpenInvoice(broken, "INV2-OLD", InvoiceStatus.SENT),
					new InvoiceRepository.OpenInvoice(later, "INV2-NEW", InvoiceStatus.SENT)));
		when(payPal.get("INV2-NEW")).thenReturn(new PayPalInvoicing.Invoice("INV2-NEW", "PAID", null, null, null));
		when(invoices.updateStatus(eq(later), eq(InvoiceStatus.PAID), any())).thenReturn(true);
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 422 })
	void oneInvoicePayPalRejectsDoesNotBlockTheOthers(int status) {
		when(payPal.get("INV2-OLD")).thenThrow(new PayPalException("PayPal rejected the request.", status));

		poller.poll();

		verify(invoices).updateStatus(later, InvoiceStatus.PAID, NOW);
		verify(service).publish(later);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 401, 500, 503 })
	void errorsThatHitEveryInvoiceEndTheRound(int status) {
		when(payPal.get("INV2-OLD")).thenThrow(new PayPalException("PayPal is unavailable.", status));

		poller.poll();

		verify(payPal, never()).get("INV2-NEW");
	}

	@Test
	void wrongCredentialsPauseTheNextRounds() {
		when(payPal.get("INV2-OLD")).thenThrow(new PayPalException("PayPal rejected the credentials.", 401));

		poller.poll();
		poller.poll();

		verify(invoices).findOpen();
	}

}
