package com.tradedesk.desk.buying;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.invoice.InvoiceTotals;
import com.tradedesk.desk.need.QuoteRow;
import com.tradedesk.desk.policy.PolicyDecision;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The buyer's negotiations ({@code negotiation}) and approvals ({@code approval}), one of each per need. */
@Repository
public class NegotiationRepository {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public NegotiationRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	// Negotiations

	/** Starts the need's counter round. False if it had already started: one round per need. */
	public boolean start(UUID needId) {
		return jdbc.sql("""
				insert into negotiation (need_id, stage) values (:needId, 'COUNTERING')
				on conflict (need_id) do nothing
				""").param("needId", needId).update() == 1;
	}

	public Optional<Negotiation> find(UUID needId) {
		return jdbc.sql("select * from negotiation where need_id = :needId")
			.param("needId", needId)
			.query((rs, n) -> new Negotiation(rs.getObject("need_id", UUID.class),
					Negotiation.Stage.valueOf(rs.getString("stage")), rs.getObject("pick_quote_id", UUID.class),
					rs.getString("reason"), source(rs.getString("reason_source"))))
			.optional();
	}

	/** Needs whose counter round was cut short (the desk restarted mid-round). */
	public List<UUID> countering() {
		return jdbc.sql("select need_id from negotiation where stage = 'COUNTERING'").query(UUID.class).list();
	}

	public void ready(UUID needId, UUID pickQuoteId, RecommendationWriter.Reason reason) {
		jdbc.sql("""
				update negotiation set stage = 'READY', pick_quote_id = :pick, reason = :reason, reason_source = :source,
				                       updated_at = now()
				 where need_id = :needId and stage = 'COUNTERING'
				""")
			.param("pick", pickQuoteId)
			.param("reason", reason.text())
			.param("source", reason.source().name())
			.param("needId", needId)
			.update();
	}

	public void noOffer(UUID needId) {
		jdbc.sql("""
				update negotiation set stage = 'NO_OFFER', updated_at = now()
				 where need_id = :needId and stage = 'COUNTERING'
				""").param("needId", needId).update();
	}

	// Approvals

	/** Stores the decision on the need's pick. False if the need already has one: one approval per need. */
	public boolean insertApproval(UUID id, UUID needId, UUID quoteId, Money total, PolicyDecision decision,
			Approval.Status status, Instant decidedAt) {
		return jdbc.sql("""
				insert into approval (id, need_id, quote_id, total_minor, currency, decision, reasons, status,
				                      decided_at)
				values (:id, :needId, :quoteId, :total, :currency, :decision, :reasons, :status, :decidedAt)
				on conflict (need_id) do nothing
				""")
			.param("id", id)
			.param("needId", needId)
			.param("quoteId", quoteId)
			.param("total", InvoiceTotals.toMinor(total.amount()))
			.param("currency", total.currency())
			.param("decision", decision.outcome().name())
			.param("reasons", json.writeValueAsString(decision.reasons()))
			.param("status", status.name())
			.param("decidedAt", decidedAt == null ? null : Timestamp.from(decidedAt))
			.update() == 1;
	}

	public Optional<Approval> approval(UUID id) {
		return jdbc.sql(SELECT_APPROVAL + " where a.id = :id").param("id", id).query(this::approvalRow).optional();
	}

	public Optional<Approval> approvalForNeed(UUID needId) {
		return jdbc.sql(SELECT_APPROVAL + " where a.need_id = :needId")
			.param("needId", needId)
			.query(this::approvalRow)
			.optional();
	}

	/** Moves a {@code PENDING} approval on. False if it wasn't pending any more (a double tap, or decided already). */
	public boolean decide(UUID id, Approval.Status status, Instant now) {
		return jdbc.sql("""
				update approval set status = :status, decided_at = :now where id = :id and status = 'PENDING'
				""")
			.param("status", status.name())
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update() == 1;
	}

	/** Blocks a {@code PENDING} approval with the policy engine's new reasons. */
	public boolean block(UUID id, PolicyDecision decision, Instant now) {
		return jdbc.sql("""
				update approval set status = 'BLOCKED', decision = :decision, reasons = :reasons, decided_at = :now
				 where id = :id and status = 'PENDING'
				""")
			.param("decision", decision.outcome().name())
			.param("reasons", json.writeValueAsString(decision.reasons()))
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update() == 1;
	}

	/** The accept's message id, set once before the first try so every retry is the same message. */
	public UUID acceptMessageId(UUID id) {
		jdbc.sql("update approval set accept_message_id = :messageId where id = :id and accept_message_id is null")
			.param("messageId", UUID.randomUUID())
			.param("id", id)
			.update();
		return jdbc.sql("select accept_message_id from approval where id = :id").param("id", id).query(UUID.class)
			.single();
	}

	public void order(UUID id, Approval.Order order, String detail) {
		jdbc.sql("update approval set order_status = :order, order_detail = :detail where id = :id")
			.param("order", order.name())
			.param("detail", detail)
			.param("id", id)
			.update();
	}

	/** Claims an unanswered order of an approved approval for one retry, so a double tap sends it once. */
	public boolean retry(UUID id) {
		return jdbc.sql("""
				update approval set order_status = 'SENDING', order_detail = null
				 where id = :id and order_status = 'UNANSWERED' and status in ('APPROVED', 'AUTO_APPROVED')
				""").param("id", id).update() == 1;
	}

	/** Approvals whose accept was in flight when the desk stopped. */
	public List<UUID> sending() {
		return jdbc.sql("select id from approval where order_status = 'SENDING'").query(UUID.class).list();
	}

	private static final String SELECT_APPROVAL = """
			select a.*, q.supplier_domain, q.supplier_name, q.simulated
			  from approval a join quote q on q.id = a.quote_id
			""";

	private Approval approvalRow(ResultSet rs, int n) throws SQLException {
		Timestamp decidedAt = rs.getTimestamp("decided_at");
		return new Approval(rs.getObject("id", UUID.class), rs.getObject("need_id", UUID.class),
				rs.getObject("quote_id", UUID.class),
				new QuoteRow.Supplier(rs.getString("supplier_domain"), rs.getString("supplier_name"),
						rs.getBoolean("simulated")),
				new Money(BigDecimal.valueOf(rs.getLong("total_minor"), 2), rs.getString("currency")),
				PolicyDecision.Outcome.valueOf(rs.getString("decision")),
				json.readValue(rs.getString("reasons"), new TypeReference<List<String>>() {
				}), Approval.Status.valueOf(rs.getString("status")),
				Approval.Order.valueOf(rs.getString("order_status")), rs.getString("order_detail"),
				rs.getTimestamp("created_at").toInstant(), decidedAt == null ? null : decidedAt.toInstant());
	}

	private static RecommendationWriter.Reason.Source source(String value) {
		return value == null ? null : RecommendationWriter.Reason.Source.valueOf(value);
	}

	/** A need's negotiation as stored. */
	public record Negotiation(UUID needId, Stage stage, UUID pickQuoteId, String reason,
			RecommendationWriter.Reason.Source reasonSource) {

		public enum Stage {

			COUNTERING, READY, NO_OFFER

		}

	}

}
