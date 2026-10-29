package com.tradedesk.desk.supplier;

import java.util.List;

import com.tradedesk.desk.contract.Unit;
import com.tradedesk.desk.invoice.InvoiceTotals;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** This desk's catalogue ({@code catalogue_item}). */
@Repository
public class CatalogueRepository {

	private final JdbcClient jdbc;

	public CatalogueRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<CatalogueItem> findAll() {
		return jdbc.sql("select * from catalogue_item order by sku")
			.query((rs, n) -> new CatalogueItem(rs.getString("sku"), rs.getString("description"),
					rs.getString("product"), rs.getString("origin"), Unit.fromWire(rs.getString("unit")),
					rs.getString("currency"), InvoiceTotals.fromMinor(rs.getLong("list_price_minor")),
					InvoiceTotals.fromMinor(rs.getLong("floor_price_minor")), rs.getBigDecimal("stock"),
					rs.getInt("lead_days")))
			.list();
	}

	public boolean isEmpty() {
		return jdbc.sql("select count(*) from catalogue_item").query(Long.class).single() == 0;
	}

	public void insert(CatalogueItem item) {
		jdbc.sql("""
				insert into catalogue_item (sku, description, product, origin, unit, currency, list_price_minor,
				                            floor_price_minor, stock, lead_days)
				values (:sku, :description, :product, :origin, :unit, :currency, :list, :floor, :stock, :leadDays)
				on conflict (sku) do nothing
				""")
			.param("sku", item.sku())
			.param("description", item.description())
			.param("product", item.product())
			.param("origin", item.origin())
			.param("unit", item.unit().wireName())
			.param("currency", item.currency())
			.param("list", InvoiceTotals.toMinor(item.listPrice()))
			.param("floor", InvoiceTotals.toMinor(item.floorPrice()))
			.param("stock", item.stock())
			.param("leadDays", item.leadDays())
			.update();
	}

}
