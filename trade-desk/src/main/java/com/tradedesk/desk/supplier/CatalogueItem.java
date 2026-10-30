package com.tradedesk.desk.supplier;

import java.math.BigDecimal;

import com.tradedesk.desk.contract.Unit;

/**
 * One thing a supplier desk sells. Prices are in whole currency units with cents ({@code 12.00}); the floor is the
 * lowest unit price this desk ever quotes, whatever the LLM or the buyer says.
 */
public record CatalogueItem(String sku, String description, String product, String origin, Unit unit, String currency,
		BigDecimal listPrice, BigDecimal floorPrice, BigDecimal stock, int leadDays) {

}
