package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tradedesk.desk.agent.QuoteBody;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.invoice.InvoiceTotals;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The café's needs and the valid quotes they got, in this desk's Postgres database. */
@Repository
public class NeedRepository {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public NeedRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Inserts the need unless its idempotency key is taken. Returns true if this call inserted it. */
	public boolean insert(UUID id, UUID key, String hash, PurchaseRequest request, NeedRow.Status status,
			int suppliersAsked) {
		return jdbc.sql("""
				insert into need (id, idempotency_key, request_hash, request, priority, status, suppliers_asked)
				values (:id, :key, :hash, :request, :priority, :status, :asked)
				on conflict (idempotency_key) do nothing
				""")
			.param("id", id)
			.param("key", key)
			.param("hash", hash)
			.param("request", json.writeValueAsString(request))
			.param("priority", request.priority().name())
			.param("status", status.name())
			.param("asked", suppliersAsked)
			.update() == 1;
	}

	public Optional<Stored> findByKey(UUID key) {
		return jdbc.sql("select id, request_hash from need where idempotency_key = :key")
			.param("key", key)
			.query((rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getString("request_hash")))
			.optional();
	}

	public Optional<NeedRow> find(UUID id) {
		return jdbc.sql(SELECT_NEED + " where n.id = :id").param("id", id).query(this::need).optional();
	}

	/** Newest first. */
	public List<NeedRow> findAll() {
		return jdbc.sql(SELECT_NEED + " order by n.created_at desc, n.id").query(this::need).list();
	}

	public void complete(UUID id) {
		jdbc.sql("update need set status = 'COMPLETE' where id = :id").param("id", id).update();
	}

	/** Stores a checked quote; a second quote from the same supplier for the same need is ignored. */
	public boolean insertQuote(UUID needId, String supplierDomain, QuoteBody quote, String note, String raw) {
		return jdbc.sql("""
				insert into quote (id, need_id, supplier_domain, supplier_name, simulated, unit_price_minor, currency,
				                   quantity, available_quantity, unit, delivery_date, valid_until, note, raw_message)
				values (:id, :needId, :domain, :name, :simulated, :price, :currency, :quantity, :available, :unit,
				        :deliveryDate, :validUntil, :note, :raw)
				on conflict (need_id, supplier_domain) do nothing
				""")
			.param("id", UUID.randomUUID())
			.param("needId", needId)
			.param("domain", supplierDomain)
			.param("name", quote.supplierName().strip())
			.param("simulated", quote.simulated())
			.param("price", InvoiceTotals.toMinor(quote.unitPrice().amount()))
			.param("currency", quote.unitPrice().currency())
			.param("quantity", quote.quantity())
			.param("available", quote.availableQuantity())
			.param("unit", quote.unit().wireName())
			.param("deliveryDate", quote.deliveryDate())
			.param("validUntil", Timestamp.from(quote.validUntil()))
			.param("note", note)
			.param("raw", raw)
			.update() == 1;
	}

	/** Unranked; {@link QuoteRanking} orders them. */
	public List<QuoteRow> quotes(UUID needId, BigDecimal requested) {
		return jdbc.sql("select * from quote where need_id = :needId").param("needId", needId).query((rs, n) -> {
			BigDecimal unitPrice = InvoiceTotals.fromMinor(rs.getLong("unit_price_minor"));
			BigDecimal quantity = rs.getBigDecimal("quantity");
			String currency = rs.getString("currency");
			return new QuoteRow(rs.getObject("id", UUID.class), needId, 0,
					new QuoteRow.Supplier(rs.getString("supplier_domain"), rs.getString("supplier_name"),
							rs.getBoolean("simulated")),
					new Money(unitPrice, currency), quantity, rs.getBigDecimal("available_quantity"),
					Unit.fromWire(rs.getString("unit")),
					new Money(quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP), currency),
					rs.getObject("delivery_date", java.time.LocalDate.class),
					rs.getTimestamp("valid_until").toInstant(), quantity.compareTo(requested) < 0,
					rs.getString("note"), rs.getTimestamp("received_at").toInstant());
		}).list();
	}

	private static final String SELECT_NEED = """
			select n.*, (select count(*) from quote q where q.need_id = n.id) as quotes_received from need n
			""";

	private NeedRow need(ResultSet rs, int n) throws SQLException {
		return new NeedRow(rs.getObject("id", UUID.class),
				json.readValue(rs.getString("request"), PurchaseRequest.class),
				NeedRow.Status.valueOf(rs.getString("status")), rs.getInt("suppliers_asked"),
				rs.getInt("quotes_received"), rs.getTimestamp("created_at").toInstant());
	}

	public record Stored(UUID id, String requestHash) {
	}

}
