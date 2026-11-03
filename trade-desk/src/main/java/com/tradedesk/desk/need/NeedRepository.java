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
import com.tradedesk.desk.policy.PolicyDecision;
import tools.jackson.core.type.TypeReference;
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

	/**
	 * Stores a checked quote; a second quote from the same supplier for the same need is ignored.
	 * @param quoteMessageId the quote envelope's {@code messageId}, which a counter or an accept names (S3)
	 */
	public boolean insertQuote(UUID needId, String supplierDomain, UUID quoteMessageId, QuoteBody quote, String note,
			String raw, PolicyDecision policy) {
		return jdbc.sql("""
				insert into quote (id, need_id, supplier_domain, supplier_name, simulated, unit_price_minor, currency,
				                   quantity, available_quantity, unit, delivery_date, valid_until, note, raw_message,
				                   quote_message_id, rfq_message_id, policy_status, policy_reasons)
				values (:id, :needId, :domain, :name, :simulated, :price, :currency, :quantity, :available, :unit,
				        :deliveryDate, :validUntil, :note, :raw, :quoteMessageId, :rfqMessageId, :policyStatus,
				        :policyReasons)
				on conflict (need_id, supplier_domain) do nothing
				""")
			.param("policyStatus", QuoteRow.PolicyStatus.of(policy.outcome()).name())
			.param("policyReasons", reasons(policy))
			.param("id", UUID.randomUUID())
			.param("quoteMessageId", quoteMessageId)
			.param("rfqMessageId", quote.rfqId())
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

	/**
	 * Stores the policy engine's latest verdict on a quote.
	 * @return true if it differs from the stored one
	 */
	public boolean updatePolicy(UUID quoteId, PolicyDecision policy) {
		return jdbc.sql("""
				update quote set policy_status = :status, policy_reasons = :reasons
				 where id = :id and (policy_status <> :status or policy_reasons <> :reasons)
				""")
			.param("status", QuoteRow.PolicyStatus.of(policy.outcome()).name())
			.param("reasons", reasons(policy))
			.param("id", quoteId)
			.update() == 1;
	}

	/** Remembers that this supplier's answer to the need's RFQ was refused; a second one for the same supplier is ignored. */
	public void insertRefusal(UUID needId, String supplierDomain, String reason) {
		jdbc.sql("""
				insert into need_refusal (need_id, supplier_domain, reason) values (:needId, :domain, :reason)
				on conflict (need_id, supplier_domain) do nothing
				""")
			.param("needId", needId)
			.param("domain", supplierDomain)
			.param("reason", reason)
			.update();
	}

	/** The engine's sentences as JSON; an {@code OK} quote has nothing to explain, so none are stored for it. */
	private String reasons(PolicyDecision policy) {
		return json.writeValueAsString(policy.outcome() == PolicyDecision.Outcome.AUTO_ACCEPT ? List.of() : policy.reasons());
	}

	/** Unranked; {@link QuoteRanking} orders them. */
	public List<QuoteRow> quotes(UUID needId, BigDecimal requested) {
		return jdbc.sql("select * from quote where need_id = :needId")
			.param("needId", needId)
			.query((rs, n) -> quoteRow(rs, requested))
			.list();
	}

	/** The ids a counter or an accept needs for one of this desk's quotes (S3). */
	public Optional<QuoteRef> quoteRef(UUID quoteId) {
		return jdbc.sql("select id, need_id, supplier_domain, quote_message_id, rfq_message_id from quote where id = :id")
			.param("id", quoteId)
			.query((rs, n) -> new QuoteRef(rs.getObject("id", UUID.class), rs.getObject("need_id", UUID.class),
					rs.getString("supplier_domain"), rs.getObject("quote_message_id", UUID.class),
					rs.getObject("rfq_message_id", UUID.class)))
			.optional();
	}

	/** Records that a counter is going out (S3). False if this quote was already countered: one counter per quote. */
	public boolean startCounter(UUID quoteId, BigDecimal target) {
		return jdbc.sql("""
				update quote set counter_unit_price_minor = :target, previous_unit_price_minor = unit_price_minor,
				                 counter_outcome = 'PENDING'
				 where id = :id and counter_outcome is null
				""")
			.param("target", InvoiceTotals.toMinor(target))
			.param("id", quoteId)
			.update() == 1;
	}

	/**
	 * Replaces the quote with the supplier's checked revision: from now on its price, stock, expiry, note and message id
	 * are the revised quote's, and an accept names the revised quote.
	 */
	public void revise(UUID quoteId, UUID revisedMessageId, QuoteBody revised, String note,
			QuoteRow.Counter.Outcome outcome) {
		jdbc.sql("""
				update quote
				   set unit_price_minor = :price, available_quantity = :available, valid_until = :validUntil,
				       note = :note, quote_message_id = :messageId, counter_outcome = :outcome
				 where id = :id and counter_outcome = 'PENDING'
				""")
			.param("price", InvoiceTotals.toMinor(revised.unitPrice().amount()))
			.param("available", revised.availableQuantity())
			.param("validUntil", Timestamp.from(revised.validUntil()))
			.param("note", note)
			.param("messageId", revisedMessageId)
			.param("outcome", outcome.name())
			.param("id", quoteId)
			.update();
	}

	/** Ends a pending counter without a new price: the quote stands as it was. */
	public void counterAnswered(UUID quoteId, QuoteRow.Counter.Outcome outcome) {
		jdbc.sql("update quote set counter_outcome = :outcome where id = :id and counter_outcome = 'PENDING'")
			.param("outcome", outcome.name())
			.param("id", quoteId)
			.update();
	}

	private QuoteRow quoteRow(ResultSet rs, BigDecimal requested) throws SQLException {
		BigDecimal unitPrice = InvoiceTotals.fromMinor(rs.getLong("unit_price_minor"));
		BigDecimal quantity = rs.getBigDecimal("quantity");
		String currency = rs.getString("currency");
		String outcome = rs.getString("counter_outcome");
		QuoteRow.Counter counter = outcome == null ? null
				: new QuoteRow.Counter(new Money(InvoiceTotals.fromMinor(rs.getLong("counter_unit_price_minor")), currency),
						new Money(InvoiceTotals.fromMinor(rs.getLong("previous_unit_price_minor")), currency),
						QuoteRow.Counter.Outcome.valueOf(outcome));
		return new QuoteRow(rs.getObject("id", UUID.class), rs.getObject("need_id", UUID.class), 0,
				new QuoteRow.Supplier(rs.getString("supplier_domain"), rs.getString("supplier_name"),
						rs.getBoolean("simulated")),
				new Money(unitPrice, currency), quantity, rs.getBigDecimal("available_quantity"),
				Unit.fromWire(rs.getString("unit")),
				new Money(quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP), currency),
				rs.getObject("delivery_date", java.time.LocalDate.class), rs.getTimestamp("valid_until").toInstant(),
				quantity.compareTo(requested) < 0, rs.getString("note"), rs.getTimestamp("received_at").toInstant(),
				counter, QuoteRow.PolicyStatus.valueOf(rs.getString("policy_status")),
				json.readValue(rs.getString("policy_reasons"), STRINGS));
	}

	private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
	};

	private static final TypeReference<List<NeedRow.Refusal>> REFUSALS = new TypeReference<>() {
	};

	private static final String SELECT_NEED = """
			select n.*, (select count(*) from quote q where q.need_id = n.id) as quotes_received,
			       (select coalesce(json_agg(json_build_object('domain', r.supplier_domain, 'reason', r.reason)
			                                 order by r.created_at, r.supplier_domain), '[]')
			          from need_refusal r where r.need_id = n.id)::text as refused
			  from need n
			""";

	private NeedRow need(ResultSet rs, int n) throws SQLException {
		return new NeedRow(rs.getObject("id", UUID.class),
				json.readValue(rs.getString("request"), PurchaseRequest.class),
				NeedRow.Status.valueOf(rs.getString("status")), rs.getInt("suppliers_asked"),
				rs.getInt("quotes_received"), json.readValue(rs.getString("refused"), REFUSALS),
				rs.getTimestamp("created_at").toInstant());
	}

	public record Stored(UUID id, String requestHash) {
	}

	/**
	 * @param quoteMessageId the supplier's message id for the quote as it stands (the revised one after a counter); null
	 *     only for a quote stored before S3 whose id couldn't be read back
	 * @param rfqMessageId the RFQ the quote answered
	 */
	public record QuoteRef(UUID id, UUID needId, String supplierDomain, UUID quoteMessageId, UUID rfqMessageId) {
	}

}
