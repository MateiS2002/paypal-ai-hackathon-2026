package com.tradedesk.desk.supplier;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.invoice.InvoiceTotals;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The quotes this supplier desk sent ({@code supplier_quote}). */
@Repository
public class SupplierQuoteRepository {

	private final JdbcClient jdbc;

	public SupplierQuoteRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(SupplierQuote quote) {
		jdbc.sql("""
				insert into supplier_quote (message_id, conversation_id, buyer_domain, rfq_id, sku, unit_price_minor,
				                            currency, quantity, available_quantity, unit, delivery_date, valid_until,
				                            note, revises, status)
				values (:messageId, :conversationId, :buyer, :rfqId, :sku, :price, :currency, :quantity, :available,
				        :unit, :deliveryDate, :validUntil, :note, :revises, :status)
				""")
			.param("messageId", quote.messageId())
			.param("conversationId", quote.conversationId())
			.param("buyer", quote.buyerDomain())
			.param("rfqId", quote.rfqId())
			.param("sku", quote.sku())
			.param("price", InvoiceTotals.toMinor(quote.unitPrice()))
			.param("currency", quote.currency())
			.param("quantity", quote.quantity())
			.param("available", quote.availableQuantity())
			.param("unit", quote.unit().wireName())
			.param("deliveryDate", quote.deliveryDate())
			.param("validUntil", Timestamp.from(quote.validUntil()))
			.param("note", quote.note())
			.param("revises", quote.revises())
			.param("status", quote.status().name())
			.update();
	}

	/**
	 * Stores the revised quote and retires the one it replaces, together. The unique {@code revises} column makes a
	 * second revision of the same quote fail with a {@code DuplicateKeyException}, so one quote is countered once.
	 */
	@Transactional
	public void revise(SupplierQuote revised) {
		insert(revised);
		jdbc.sql("update supplier_quote set status = 'SUPERSEDED' where message_id = :id and status = 'OPEN'")
			.param("id", revised.revises())
			.update();
	}

	public Optional<SupplierQuote> find(UUID messageId) {
		return jdbc.sql("select * from supplier_quote where message_id = :id")
			.param("id", messageId)
			.query(SupplierQuoteRepository::quote)
			.optional();
	}

	/** Marks the quote accepted with its invoice. False if it was no longer open (or accepted with that invoice). */
	public boolean accept(UUID messageId, UUID invoiceId) {
		return jdbc.sql("""
				update supplier_quote set status = 'ACCEPTED', invoice_id = :invoiceId
				 where message_id = :id and (status = 'OPEN' or invoice_id = :invoiceId)
				""")
			.param("id", messageId)
			.param("invoiceId", invoiceId)
			.update() == 1;
	}

	private static SupplierQuote quote(ResultSet rs, int n) throws SQLException {
		return new SupplierQuote(rs.getObject("message_id", UUID.class), rs.getObject("conversation_id", UUID.class),
				rs.getString("buyer_domain"), rs.getObject("rfq_id", UUID.class), rs.getString("sku"),
				InvoiceTotals.fromMinor(rs.getLong("unit_price_minor")), rs.getString("currency"),
				rs.getBigDecimal("quantity"), rs.getBigDecimal("available_quantity"),
				Unit.fromWire(rs.getString("unit")), rs.getObject("delivery_date", LocalDate.class),
				rs.getTimestamp("valid_until").toInstant(), rs.getString("note"),
				rs.getObject("revises", UUID.class), SupplierQuote.Status.valueOf(rs.getString("status")),
				rs.getObject("invoice_id", UUID.class));
	}

}
