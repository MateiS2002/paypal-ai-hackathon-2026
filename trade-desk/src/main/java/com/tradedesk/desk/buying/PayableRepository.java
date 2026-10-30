package com.tradedesk.desk.buying;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.invoice.InvoiceStatus;
import com.tradedesk.desk.invoice.InvoiceTotals;
import com.tradedesk.desk.need.QuoteRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Invoices this desk has to pay ({@code payable}), one per approval. */
@Repository
public class PayableRepository {

	private final JdbcClient jdbc;

	public PayableRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Stores a checked invoice. Returns the stored row: the existing one if this approval already has a payable. */
	public PayableRow insert(UUID approvalId, UUID needId, QuoteRow.Supplier supplier, String description,
			String payPalInvoiceId, String payerViewUrl, Money total, InvoiceStatus status, Instant statusAt) {
		jdbc.sql("""
				insert into payable (id, need_id, approval_id, supplier_domain, supplier_name, simulated, description,
				                     paypal_invoice_id, payer_view_url, total_minor, currency, status, status_at)
				values (:id, :needId, :approvalId, :domain, :name, :simulated, :description, :payPalId, :url, :total,
				        :currency, :status, :statusAt)
				on conflict (approval_id) do nothing
				""")
			.param("id", UUID.randomUUID())
			.param("needId", needId)
			.param("approvalId", approvalId)
			.param("domain", supplier.domain())
			.param("name", supplier.name())
			.param("simulated", supplier.simulated())
			.param("description", description)
			.param("payPalId", payPalInvoiceId)
			.param("url", payerViewUrl)
			.param("total", InvoiceTotals.toMinor(total.amount()))
			.param("currency", total.currency())
			.param("status", status.name())
			.param("statusAt", Timestamp.from(statusAt))
			.update();
		return jdbc.sql(SELECT + " where approval_id = :approvalId")
			.param("approvalId", approvalId)
			.query(PayableRepository::row)
			.single();
	}

	/** Newest first. */
	public List<PayableRow> findAll() {
		return jdbc.sql(SELECT + " order by created_at desc, id").query(PayableRepository::row).list();
	}

	public Optional<Stored> findByPayPalId(String payPalInvoiceId) {
		return jdbc.sql(SELECT + " where paypal_invoice_id = :payPalId")
			.param("payPalId", payPalInvoiceId)
			.query((rs, n) -> new Stored(row(rs, n), rs.getTimestamp("status_at").toInstant()))
			.optional();
	}

	/**
	 * Records the supplier's report if it is newer than the one held, so reports that arrive out of order can't move
	 * an invoice back. True if the status changed.
	 */
	public boolean updateStatus(UUID id, InvoiceStatus status, Instant statusAt) {
		return jdbc.sql("""
				update payable set status = :status, status_at = :statusAt, updated_at = now()
				 where id = :id and status_at < :statusAt and status <> :status
				""")
			.param("status", status.name())
			.param("statusAt", Timestamp.from(statusAt))
			.param("id", id)
			.update() == 1;
	}

	public Optional<PayableRow> find(UUID id) {
		return jdbc.sql(SELECT + " where id = :id").param("id", id).query(PayableRepository::row).optional();
	}

	private static final String SELECT = "select * from payable";

	private static PayableRow row(ResultSet rs, int n) throws SQLException {
		return new PayableRow(rs.getObject("id", UUID.class), rs.getObject("need_id", UUID.class),
				new QuoteRow.Supplier(rs.getString("supplier_domain"), rs.getString("supplier_name"),
						rs.getBoolean("simulated")),
				rs.getString("description"), rs.getString("paypal_invoice_id"), rs.getString("payer_view_url"),
				new Money(InvoiceTotals.fromMinor(rs.getLong("total_minor")), rs.getString("currency")),
				InvoiceStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}

	/** A payable plus when its status was reported, which only the desk needs. */
	public record Stored(PayableRow row, Instant statusAt) {
	}

}
