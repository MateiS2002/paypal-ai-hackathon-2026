package com.tradedesk.desk.invoice;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.tradedesk.desk.paypal.PayPalClient;
import com.tradedesk.desk.paypal.PayPalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks PayPal for the status of every open invoice and pushes changes to the ledger over SSE. Polling, not webhooks:
 * PayPal can't reach {@code localhost} under docker compose. Open invoices are read from Postgres each round, so
 * after a restart the poller picks them up again.
 */
@Component
public class InvoicePoller {

	private static final Logger log = LoggerFactory.getLogger(InvoicePoller.class);

	private static final Duration CREDENTIALS_PAUSE = Duration.ofMinutes(1);

	/** Only the scheduler thread reads and writes this. */
	private Instant pausedUntil = Instant.MIN;

	private final InvoiceRepository invoices;

	private final InvoiceService service;

	private final PayPalClient payPal;

	private final Clock clock;

	public InvoicePoller(InvoiceRepository invoices, InvoiceService service, PayPalClient payPal, Clock clock) {
		this.invoices = invoices;
		this.service = service;
		this.payPal = payPal;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${desk.invoice-poll-interval:PT5S}",
			initialDelayString = "${desk.invoice-poll-interval:PT5S}")
	public void poll() {
		if (!payPal.configured() || clock.instant().isBefore(pausedUntil)) {
			return;
		}
		for (InvoiceRepository.OpenInvoice open : invoices.findOpen()) {
			InvoiceStatus status;
			try {
				status = InvoiceStatus.fromPayPal(payPal.get(open.payPalInvoiceId()).status());
			}
			catch (PayPalException ex) {
				// PayPalClient already logged why. Wrong credentials won't fix themselves, so don't repeat that log
				// every few seconds; anything else is retried next round.
				if (ex.status() == 401) {
					pausedUntil = clock.instant().plus(CREDENTIALS_PAUSE);
				}
				return;
			}
			if (status != open.status() && invoices.updateStatus(open.id(), status, clock.instant())) {
				log.info("Invoice {} is now {} (was {})", open.id(), status, open.status());
				service.publish(open.id());
			}
		}
	}

}
