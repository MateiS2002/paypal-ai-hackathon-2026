package com.tradedesk.desk.policy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.invoice.InvoiceTotals;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The one-row {@code policy} table, seeded with the defaults by the V4 migration. */
@Repository
public class PolicyRepository {

	private final JdbcClient jdbc;

	public PolicyRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Policy get() {
		return jdbc.sql("select * from policy where id = 1").query((rs, n) -> {
			String currency = rs.getString("currency");
			return new Policy(money(rs.getLong("max_unit_price_minor"), currency),
					money(rs.getLong("approval_above_minor"), currency),
					money(rs.getLong("max_order_total_minor"), currency),
					rs.getTimestamp("updated_at").toInstant());
		}).single();
	}

	/** Stores an already checked policy. */
	public Policy save(Policy policy, Instant now) {
		jdbc.sql("""
				update policy
				   set currency = :currency, max_unit_price_minor = :maxUnit, approval_above_minor = :approval,
				       max_order_total_minor = :maxTotal, updated_at = :now
				 where id = 1
				""")
			.param("currency", policy.currency())
			.param("maxUnit", InvoiceTotals.toMinor(policy.maxUnitPrice().amount()))
			.param("approval", InvoiceTotals.toMinor(policy.approvalAbove().amount()))
			.param("maxTotal", InvoiceTotals.toMinor(policy.maxOrderTotal().amount()))
			.param("now", Timestamp.from(now))
			.update();
		return get();
	}

	private static Money money(long minor, String currency) {
		BigDecimal amount = InvoiceTotals.fromMinor(minor);
		return new Money(amount, currency);
	}

}
