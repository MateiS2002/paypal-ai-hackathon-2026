package com.tradedesk.desk.invoice;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Invoices, their lines, customers and idempotency records, in this desk's Postgres database. */
@Repository
public class InvoiceRepository {

	private final JdbcClient jdbc;

	public InvoiceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// Customers

	/** Finds the customer by (lower-case) email, or creates it. Safe when two requests race. */
	public UUID findOrCreateCustomer(String name, String email) {
		jdbc.sql("insert into customer (id, name, email) values (:id, :name, :email) on conflict (email) do nothing")
			.param("id", UUID.randomUUID())
			.param("name", name)
			.param("email", email)
			.update();
		return jdbc.sql("select id from customer where email = :email").param("email", email).query(UUID.class).single();
	}

	public List<NewInvoice.Customer> customers() {
		return jdbc.sql("select name, email from customer order by name, email")
			.query((rs, n) -> new NewInvoice.Customer(rs.getString("name"), rs.getString("email")))
			.list();
	}

	// Invoices

	public void insertInvoice(UUID id, UUID customerId, InvoiceTotals.Priced priced, InvoiceStatus status,
			InvoiceSource source, String sourceText, LocalDate dueDate, NegotiationLink negotiation) {
		jdbc.sql("""
				insert into invoice (id, customer_id, customer_name, customer_email, total_minor, currency, status,
				                     due_date, source, source_text, conversation_id, buyer_domain)
				values (:id, :customerId, :name, :email, :total, :currency, :status, :dueDate, :source, :sourceText,
				        :conversationId, :buyerDomain)
				""")
			.param("id", id)
			.param("customerId", customerId)
			.param("name", priced.customer().name())
			.param("email", priced.customer().email())
			.param("total", InvoiceTotals.toMinor(priced.total()))
			.param("currency", priced.currency())
			.param("status", status.name())
			.param("dueDate", dueDate)
			.param("source", source.name())
			.param("sourceText", sourceText)
			.param("conversationId", negotiation == null ? null : negotiation.conversationId())
			.param("buyerDomain", negotiation == null ? null : negotiation.buyerDomain())
			.update();
		int lineNo = 1;
		for (InvoiceTotals.PricedLine line : priced.lines()) {
			jdbc.sql("""
					insert into invoice_line (invoice_id, line_no, description, quantity, unit_price_minor,
					                          line_total_minor)
					values (:invoiceId, :lineNo, :description, :quantity, :unitPrice, :lineTotal)
					""")
				.param("invoiceId", id)
				.param("lineNo", lineNo++)
				.param("description", line.description())
				.param("quantity", line.quantity())
				.param("unitPrice", InvoiceTotals.toMinor(line.unitPrice()))
				.param("lineTotal", InvoiceTotals.toMinor(line.lineTotal()))
				.update();
		}
	}

	public void setInvoiceNumber(UUID id, String invoiceNumber) {
		jdbc.sql("update invoice set invoice_number = :number, updated_at = now() where id = :id")
			.param("number", invoiceNumber)
			.param("id", id)
			.update();
	}

	/** Records what PayPal returned after creating or sending the invoice. */
	public void recordPayPal(UUID id, String payPalInvoiceId, String invoiceNumber, InvoiceStatus status,
			LocalDate dueDate, String payerViewUrl) {
		jdbc.sql("""
				update invoice
				   set paypal_invoice_id = :payPalId,
				       invoice_number = coalesce(:number, invoice_number),
				       status = :status,
				       paid_at = case when :status = 'PAID' then coalesce(paid_at, now()) else paid_at end,
				       due_date = coalesce(:dueDate, due_date),
				       payer_view_url = coalesce(:payerViewUrl, payer_view_url),
				       updated_at = now()
				 where id = :id
				""")
			.param("payPalId", payPalInvoiceId)
			.param("number", invoiceNumber)
			.param("status", status.name())
			.param("dueDate", dueDate)
			.param("payerViewUrl", payerViewUrl)
			.param("id", id)
			.update();
	}

	/** Sets a new status from the poller. Returns false if the status was already this one. */
	public boolean updateStatus(UUID id, InvoiceStatus status, Instant now) {
		return jdbc.sql("""
				update invoice
				   set status = :status,
				       paid_at = case when :status = 'PAID' then coalesce(paid_at, :now) else paid_at end,
				       updated_at = :now
				 where id = :id and status <> :status
				""")
			.param("status", status.name())
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update() == 1;
	}

	public Optional<StoredInvoice> find(UUID id) {
		Optional<StoredInvoice> invoice = jdbc.sql("select * from invoice where id = :id")
			.param("id", id)
			.query(InvoiceRepository::stored)
			.optional();
		return invoice.map(found -> found.withLines(lines(List.of(id)).getOrDefault(id, List.of())));
	}

	/** The ledger, newest first. */
	public List<InvoiceRow> findAll() {
		List<StoredInvoice> invoices = jdbc.sql("select * from invoice order by created_at desc, id")
			.query(InvoiceRepository::stored)
			.list();
		Map<UUID, List<InvoiceRow.Line>> lines = lines(invoices.stream().map(i -> i.row().id()).toList());
		return invoices.stream().map(i -> i.withLines(lines.getOrDefault(i.row().id(), List.of())).row()).toList();
	}

	/** Invoices PayPal can still move on, for the poller. Read from the database, so a restart picks them up. */
	public List<OpenInvoice> findOpen() {
		List<String> open = Arrays.stream(InvoiceStatus.values())
			.filter(InvoiceStatus::isOpen)
			.map(Enum::name)
			.toList();
		return jdbc.sql("""
				select id, paypal_invoice_id, status from invoice
				 where paypal_invoice_id is not null and status in (:open)
				 order by created_at
				""")
			.param("open", open)
			.query((rs, n) -> new OpenInvoice(rs.getObject("id", UUID.class), rs.getString("paypal_invoice_id"),
					InvoiceStatus.valueOf(rs.getString("status"))))
			.list();
	}

	// Buyer notifications (S3)

	/**
	 * Negotiation invoices whose buyer hasn't acknowledged the current status yet, oldest change first. Read from the
	 * database each round, so a status the buyer missed (its desk was down) is sent again. Only invoices the buyer
	 * already knows from the accept's {@code invoice} reply ({@code buyer_notified_status} set): one still being
	 * created is the reply's job, not this push's.
	 */
	public List<BuyerUpdate> findBuyerUpdates() {
		return jdbc.sql("""
				select id, paypal_invoice_id, status, updated_at, conversation_id, buyer_domain from invoice
				 where source = 'NEGOTIATION' and buyer_domain is not null and paypal_invoice_id is not null
				   and buyer_notified_status is not null and buyer_notified_status <> status
				 order by updated_at
				""")
			.query((rs, n) -> new BuyerUpdate(rs.getObject("id", UUID.class), rs.getString("paypal_invoice_id"),
					InvoiceStatus.valueOf(rs.getString("status")), instant(rs, "updated_at"),
					new NegotiationLink(rs.getObject("conversation_id", UUID.class), rs.getString("buyer_domain"))))
			.list();
	}

	/** Records that the buyer has {@code status}; a later change makes the invoice show up in the next round again. */
	public void markBuyerNotified(UUID id, InvoiceStatus status) {
		jdbc.sql("update invoice set buyer_notified_status = :status where id = :id")
			.param("status", status.name())
			.param("id", id)
			.update();
	}

	// Idempotency

	public void insertOperation(UUID key, String requestHash, UUID payPalRequestId, UUID invoiceId) {
		jdbc.sql("""
				insert into invoice_operation (idempotency_key, request_hash, paypal_request_id, step, invoice_id)
				values (:key, :hash, :requestId, 'CREATING', :invoiceId)
				""")
			.param("key", key)
			.param("hash", requestHash)
			.param("requestId", payPalRequestId)
			.param("invoiceId", invoiceId)
			.update();
	}

	public Optional<Operation> findOperation(UUID key) {
		return jdbc.sql("select * from invoice_operation where idempotency_key = :key")
			.param("key", key)
			.query((rs, n) -> new Operation(rs.getObject("idempotency_key", UUID.class), rs.getString("request_hash"),
					rs.getObject("paypal_request_id", UUID.class), Operation.Step.valueOf(rs.getString("step")),
					rs.getObject("invoice_id", UUID.class)))
			.optional();
	}

	public void updateStep(UUID key, Operation.Step step) {
		jdbc.sql("update invoice_operation set step = :step, updated_at = now() where idempotency_key = :key")
			.param("step", step.name())
			.param("key", key)
			.update();
	}

	private Map<UUID, List<InvoiceRow.Line>> lines(List<UUID> invoiceIds) {
		Map<UUID, List<InvoiceRow.Line>> byInvoice = new LinkedHashMap<>();
		if (invoiceIds.isEmpty()) {
			return byInvoice;
		}
		jdbc.sql("""
				select l.*, i.currency from invoice_line l join invoice i on i.id = l.invoice_id
				 where l.invoice_id in (:ids) order by l.invoice_id, l.line_no
				""")
			.param("ids", invoiceIds)
			.query((ResultSet rs) -> {
				String currency = rs.getString("currency");
				byInvoice.computeIfAbsent(rs.getObject("invoice_id", UUID.class), id -> new ArrayList<>())
					.add(new InvoiceRow.Line(rs.getString("description"), rs.getBigDecimal("quantity"),
							money(rs.getLong("unit_price_minor"), currency),
							money(rs.getLong("line_total_minor"), currency)));
			});
		return byInvoice;
	}

	private static StoredInvoice stored(ResultSet rs, int rowNum) throws SQLException {
		String currency = rs.getString("currency");
		InvoiceRow row = new InvoiceRow(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
				new NewInvoice.Customer(rs.getString("customer_name"), rs.getString("customer_email")), List.of(),
				money(rs.getLong("total_minor"), currency), InvoiceStatus.valueOf(rs.getString("status")),
				InvoiceSource.valueOf(rs.getString("source")), rs.getObject("due_date", LocalDate.class),
				rs.getString("payer_view_url"), instant(rs, "created_at"), instant(rs, "updated_at"),
				instant(rs, "paid_at"));
		return new StoredInvoice(row, rs.getString("paypal_invoice_id"));
	}

	private static Money money(long minor, String currency) {
		return new Money(InvoiceTotals.fromMinor(minor), currency);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	/** A ledger row plus the PayPal invoice id, which only the desk needs. */
	public record StoredInvoice(InvoiceRow row, String payPalInvoiceId) {

		StoredInvoice withLines(List<InvoiceRow.Line> lines) {
			InvoiceRow r = row;
			return new StoredInvoice(new InvoiceRow(r.id(), r.invoiceNumber(), r.customer(), List.copyOf(lines),
					r.total(), r.status(), r.source(), r.dueDate(), r.payerViewUrl(), r.createdAt(), r.updatedAt(),
					r.paidAt()), payPalInvoiceId);
		}

	}

	public record OpenInvoice(UUID id, String payPalInvoiceId, InvoiceStatus status) {
	}

	/** A negotiation invoice whose buyer should hear its current status. */
	public record BuyerUpdate(UUID id, String payPalInvoiceId, InvoiceStatus status, Instant updatedAt,
			NegotiationLink negotiation) {
	}

	/** One {@code Idempotency-Key} and how far its invoice got. */
	public record Operation(UUID key, String requestHash, UUID payPalRequestId, Step step, UUID invoiceId) {

		public enum Step {

			CREATING, CREATED, SENT

		}

	}

}
