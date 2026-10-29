package com.tradedesk.desk.invoice;

import java.util.Locale;

import com.tradedesk.desk.paypal.PayPalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo's customer, Bean There, with the sandbox buyer's email, so the new-invoice form can pick it. Only on
 * desks that send invoices; the email comes from {@code PAYPAL_SANDBOX_BUYER_EMAIL}, so it isn't in the migration.
 */
@Component
class DemoCustomerSeeder implements ApplicationRunner {

	static final String DEMO_CUSTOMER = "Bean There";

	private static final Logger log = LoggerFactory.getLogger(DemoCustomerSeeder.class);

	private final InvoiceRepository invoices;

	private final PayPalProperties payPal;

	DemoCustomerSeeder(InvoiceRepository invoices, PayPalProperties payPal) {
		this.invoices = invoices;
		this.payPal = payPal;
	}

	@Override
	public void run(ApplicationArguments args) {
		String email = payPal.sandboxBuyerEmail();
		if (!payPal.configured() || email == null || !email.contains("@")) {
			return;
		}
		invoices.findOrCreateCustomer(DEMO_CUSTOMER, email.strip().toLowerCase(Locale.ROOT));
		log.info("Demo customer {} is ready", DEMO_CUSTOMER);
	}

}
