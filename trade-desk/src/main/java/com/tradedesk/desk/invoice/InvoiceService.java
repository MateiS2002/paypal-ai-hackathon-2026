package com.tradedesk.desk.invoice;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.events.DeskEvent;
import com.tradedesk.desk.events.DeskEventStream;
import com.tradedesk.desk.invoice.InvoiceRepository.Operation;
import com.tradedesk.desk.paypal.PayPalClient;
import com.tradedesk.desk.paypal.PayPalException;
import com.tradedesk.desk.paypal.PayPalInvoicing;
import com.tradedesk.desk.paypal.PayPalNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates and sends invoices through PayPal. Every money flow ends here: the owner's form (S1), an approved
 * negotiation (S3) and a messy order (S7).
 *
 * <p>Idempotent per {@code Idempotency-Key}: an {@code invoice_operation} row is written before any PayPal call and
 * records how far the invoice got (CREATING, CREATED, SENT). A retry with the same key and body resumes from that
 * step with the same {@code PayPal-Request-Id}, so a double click, a timeout or a crash never creates a second PayPal
 * invoice. The same key with a different body is refused.
 */
@Service
public class InvoiceService {

	/** PayPal has no NET_14 term, so invoices get an explicit due date this many days out. */
	static final int DUE_IN_DAYS = 14;

	/** {@code DESK_INVOICE_APPROVAL_ABOVE} is an amount in this currency. */
	static final String APPROVAL_LIMIT_CURRENCY = "USD";

	private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

	/** One desk is one JVM, so striped locks are enough to serialise requests that share a key. */
	private final Object[] keyLocks = new Object[64];

	private final InvoiceRepository invoices;

	private final PayPalClient payPal;

	private final DeskEventStream events;

	private final DeskProperties desk;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public InvoiceService(InvoiceRepository invoices, PayPalClient payPal, DeskEventStream events,
			DeskProperties desk, TransactionTemplate transaction, Clock clock) {
		this.invoices = invoices;
		this.payPal = payPal;
		this.events = events;
		this.desk = desk;
		this.transaction = transaction;
		this.clock = clock;
		for (int i = 0; i < keyLocks.length; i++) {
			keyLocks[i] = new Object();
		}
	}

	/**
	 * Creates the invoice and sends it through PayPal, or, for a messy order above the approval limit, holds it as
	 * {@link InvoiceStatus#AWAITING_APPROVAL} without calling PayPal.
	 * @param idempotencyKey the caller's key for this one invoice, reused on every retry
	 * @param request what to invoice; checked by {@link InvoiceTotals#price} before anything is stored or sent
	 * @param source where the invoice came from
	 * @param sourceText the original order text, for {@link InvoiceSource#MESSY_ORDER} only
	 * @throws InvalidInvoiceException if the request breaks a rule (nothing is stored or sent)
	 * @throws IdempotencyConflictException if the key was used for a different request
	 * @throws PayPalException if PayPal failed; the progress is stored, so a retry with the same key resumes
	 */
	public Result create(UUID idempotencyKey, NewInvoice request, InvoiceSource source, String sourceText) {
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		Objects.requireNonNull(source, "source");
		String text = source == InvoiceSource.MESSY_ORDER ? sourceText : null;
		InvoiceTotals.Priced priced = InvoiceTotals.price(request);
		boolean hold = needsApproval(source, priced, desk.invoiceApprovalAbove());
		String hash = requestHash(priced, source, text);

		synchronized (keyLocks[Math.floorMod(idempotencyKey.hashCode(), keyLocks.length)]) {
			Optional<Operation> existing = invoices.findOperation(idempotencyKey);
			if (existing.isPresent() && !existing.get().requestHash().equals(hash)) {
				throw new IdempotencyConflictException();
			}
			// Only a new invoice needs PayPal up front; a retry of a finished one returns the stored row.
			if (existing.isEmpty() && !hold && !payPal.configured()) {
				throw new PayPalNotConfiguredException();
			}
			Operation operation = existing
				.orElseGet(() -> start(idempotencyKey, priced, hash, hold, source, text));
			boolean created = existing.isEmpty();
			try {
				if (operation.step() != Operation.Step.SENT && !isHeld(operation.invoiceId())) {
					advance(operation);
				}
			}
			finally {
				if (created || operation.step() != Operation.Step.SENT) {
					publish(operation.invoiceId());
				}
			}
			return new Result(row(operation.invoiceId()), created);
		}
	}

	public List<InvoiceRow> ledger() {
		return invoices.findAll();
	}

	public List<NewInvoice.Customer> customers() {
		return invoices.customers();
	}

	/** Stores the invoice, its lines and the operation in one transaction, before any PayPal call. */
	private Operation start(UUID key, InvoiceTotals.Priced priced, String hash, boolean hold, InvoiceSource source,
			String sourceText) {
		UUID invoiceId = UUID.randomUUID();
		UUID payPalRequestId = UUID.randomUUID();
		LocalDate dueDate = LocalDate.now(clock.withZone(desk.timeZone())).plusDays(DUE_IN_DAYS);
		transaction.executeWithoutResult(status -> {
			UUID customerId = invoices.findOrCreateCustomer(priced.customer().name(), priced.customer().email());
			invoices.insertInvoice(invoiceId, customerId, priced,
					hold ? InvoiceStatus.AWAITING_APPROVAL : InvoiceStatus.DRAFT, source, sourceText, dueDate);
			invoices.insertOperation(key, hash, payPalRequestId, invoiceId);
		});
		if (hold) {
			log.info("Invoice {} is above the approval limit and waits for the owner", invoiceId);
		}
		return new Operation(key, hash, payPalRequestId, Operation.Step.CREATING, invoiceId);
	}

	/** Moves the operation on from its stored step: create the draft if needed, check the total, send. */
	private void advance(Operation operation) {
		InvoiceRepository.StoredInvoice stored = invoices.find(operation.invoiceId()).orElseThrow();
		InvoiceRow row = stored.row();
		PayPalInvoicing.Invoice remote;

		if (operation.step() == Operation.Step.CREATING) {
			String number = row.invoiceNumber();
			if (number == null) {
				number = payPal.nextInvoiceNumber();
				invoices.setInvoiceNumber(row.id(), number);
			}
			remote = payPal.createDraft(operation.payPalRequestId().toString(), draft(row, number));
			invoices.recordPayPal(row.id(), remote.id(), remote.invoiceNumber(),
					InvoiceStatus.fromPayPal(remote.status()), remote.dueDate(), remote.payerViewUrl());
			invoices.updateStep(operation.key(), Operation.Step.CREATED);
			log.info("Created PayPal invoice {} for invoice {}", remote.id(), row.id());
		}
		else {
			remote = payPal.get(stored.payPalInvoiceId());
		}

		if (remote.amount() == null) {
			remote = payPal.get(remote.id());
		}
		checkTotal(row, remote);

		String payerViewUrl = remote.payerViewUrl();
		InvoiceStatus status = InvoiceStatus.fromPayPal(remote.status());
		if (status == InvoiceStatus.DRAFT) {
			String link = payPal.send(remote.id(), operation.payPalRequestId() + "-send");
			if (link == null && payerViewUrl == null) {
				payerViewUrl = payPal.get(remote.id()).payerViewUrl();
			}
			else if (link != null) {
				payerViewUrl = link;
			}
			status = InvoiceStatus.SENT;
			log.info("Sent PayPal invoice {}", remote.id());
		}
		invoices.recordPayPal(row.id(), remote.id(), remote.invoiceNumber(), status, remote.dueDate(), payerViewUrl);
		invoices.updateStep(operation.key(), Operation.Step.SENT);
	}

	/**
	 * Our total is the one the owner agreed to; PayPal must show the same, to the cent, or the invoice is not sent.
	 */
	private void checkTotal(InvoiceRow row, PayPalInvoicing.Invoice remote) {
		BigDecimal ours = row.total().amount();
		PayPalInvoicing.Amount theirs = remote.amount();
		boolean matches = theirs != null && theirs.value() != null && row.total().currency().equals(theirs.currencyCode())
				&& new BigDecimal(theirs.value()).compareTo(ours) == 0;
		if (!matches) {
			log.warn("PayPal invoice {} total {} {} does not match the desk's {} {}; not sending", remote.id(),
					theirs == null ? null : theirs.value(), theirs == null ? null : theirs.currencyCode(),
					ours.toPlainString(), row.total().currency());
			throw new PayPalException("PayPal calculated a different total than the desk ("
					+ (theirs == null ? "none" : theirs.value()) + " instead of " + ours.toPlainString()
					+ "), so the invoice was not sent.", 200);
		}
	}

	private PayPalInvoicing.Draft draft(InvoiceRow row, String invoiceNumber) {
		return new PayPalInvoicing.Draft(invoiceNumber, desk.name(), row.customer().name(), row.customer().email(),
				row.total().currency(), row.dueDate(),
				row.lines()
					.stream()
					.map(line -> new PayPalInvoicing.Item(line.description(), line.quantity(),
							line.unitPrice().amount()))
					.toList());
	}

	static boolean needsApproval(InvoiceSource source, InvoiceTotals.Priced priced, BigDecimal limit) {
		// MANUAL invoices are typed by the owner and NEGOTIATION ones were approved on the café side: never held.
		if (source != InvoiceSource.MESSY_ORDER) {
			return false;
		}
		// Fail closed: with no limit set, or an amount in another currency than the limit's, the owner decides.
		return limit == null || !APPROVAL_LIMIT_CURRENCY.equals(priced.currency())
				|| priced.total().compareTo(limit) > 0;
	}

	private boolean isHeld(UUID invoiceId) {
		return row(invoiceId).status() == InvoiceStatus.AWAITING_APPROVAL;
	}

	private InvoiceRow row(UUID invoiceId) {
		return invoices.find(invoiceId).orElseThrow().row();
	}

	void publish(UUID invoiceId) {
		events.publish(new DeskEvent("invoice.updated", row(invoiceId)));
	}

	/** A fingerprint of the checked request, so the same key with a different invoice is caught. */
	static String requestHash(InvoiceTotals.Priced priced, InvoiceSource source, String sourceText) {
		StringBuilder canonical = new StringBuilder()
			.append(source).append('\u001f')
			.append(priced.customer().name()).append('\u001f')
			.append(priced.customer().email()).append('\u001f')
			.append(priced.currency()).append('\u001f')
			.append(Objects.toString(sourceText, "")).append('\u001e');
		for (InvoiceTotals.PricedLine line : priced.lines()) {
			canonical.append(line.description()).append('\u001f')
				.append(line.quantity().toPlainString()).append('\u001f')
				.append(line.unitPrice().toPlainString()).append('\u001e');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * @param invoice the invoice as stored after this call
	 * @param created true if this call created it, false for a retry of an earlier call
	 */
	public record Result(InvoiceRow invoice, boolean created) {
	}

}
