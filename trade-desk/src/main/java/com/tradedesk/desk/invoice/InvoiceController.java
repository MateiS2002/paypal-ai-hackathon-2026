package com.tradedesk.desk.invoice;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The ledger and the owner's new-invoice form. Routes are listed in contracts/desk-api.md. */
@RestController
@RequestMapping("/api")
public class InvoiceController {

	private final InvoiceService invoices;

	public InvoiceController(InvoiceService invoices) {
		this.invoices = invoices;
	}

	@GetMapping("/invoices")
	public List<InvoiceRow> ledger() {
		return invoices.ledger();
	}

	/**
	 * Creates and sends an invoice typed by the owner. Always {@link InvoiceSource#MANUAL}: this route takes no source
	 * text, so it can't create a messy-order invoice. 201 when created, 200 for a retry with the same key.
	 */
	@PostMapping("/invoices")
	public ResponseEntity<InvoiceRow> create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
			@RequestBody NewInvoice request) {
		InvoiceService.Result result = invoices.create(idempotencyKey, request, InvoiceSource.MANUAL, null);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.invoice());
	}

	/** Known customers, for the new-invoice form. */
	@GetMapping("/customers")
	public List<NewInvoice.Customer> customers() {
		return invoices.customers();
	}

}
