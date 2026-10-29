package com.tradedesk.desk.supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.tradedesk.desk.agent.RfqBody;

/**
 * The supplier's money rules, in Java and never in a prompt: which item answers an RFQ, at what unit price, how much
 * and by when. The unit price is never below the item's floor and the quantity never above its stock; the LLM only
 * writes the note afterwards and can't change any of this.
 */
public final class QuotePricer {

	static final BigDecimal MAX_QUANTITY = new BigDecimal("100000");

	/** Volume discounts off the list price, largest first: at least this quantity gets this discount. */
	static final List<Discount> DISCOUNTS = List.of(new Discount(new BigDecimal("200"), new BigDecimal("0.08")),
			new Discount(new BigDecimal("50"), new BigDecimal("0.05")));

	/** Words that don't say what the product is, so they don't count as a match on their own. */
	private static final Set<String> FILLER = Set.of("green", "raw", "whole", "the", "of", "a", "an", "and", "for",
			"some", "fresh", "good", "quality", "premium", "specialty", "unroasted");

	private QuotePricer() {
	}

	/**
	 * @param today today in this desk's time zone
	 * @return an offer, or a refusal with a reason the buyer can show as is
	 */
	public static Decision price(RfqBody rfq, List<CatalogueItem> catalogue, LocalDate today) {
		Optional<String> malformed = malformed(rfq);
		if (malformed.isPresent()) {
			return new Refusal("The RFQ is malformed: " + malformed.get() + ".");
		}
		List<CatalogueItem> matches = catalogue.stream()
			.filter(item -> sameProduct(rfq.product(), item) && sameOrigin(rfq.origin(), item.origin()))
			.toList();
		if (matches.isEmpty()) {
			return new Refusal("We don't sell " + rfq.product()
					+ (rfq.origin() == null ? "" : " from " + rfq.origin()) + ".");
		}
		List<CatalogueItem> inUnit = matches.stream().filter(item -> item.unit() == rfq.unit()).toList();
		if (inUnit.isEmpty()) {
			return new Refusal("We only sell this by the " + matches.getFirst().unit().wireName() + ".");
		}
		List<CatalogueItem> inStock = inUnit.stream().filter(item -> item.stock().signum() > 0).toList();
		if (inStock.isEmpty()) {
			return new Refusal("Out of stock.");
		}
		BigDecimal wanted = rfq.quantity();
		// Prefer an item that covers the whole quantity, then the cheapest.
		CatalogueItem item = inStock.stream()
			.min(Comparator.comparing((CatalogueItem i) -> i.stock().compareTo(wanted) < 0)
				.thenComparing(CatalogueItem::listPrice))
			.orElseThrow();

		BigDecimal quantity = wanted.min(item.stock()).setScale(3, RoundingMode.DOWN);
		if (quantity.signum() <= 0) {
			return new Refusal("Out of stock.");
		}
		BigDecimal unitPrice = unitPrice(item, quantity);
		Offer offer = new Offer(item, unitPrice, quantity, item.stock().setScale(3, RoundingMode.DOWN),
				today.plusDays(item.leadDays()), quantity.compareTo(wanted) < 0);
		enforce(offer);
		return offer;
	}

	/** List price less the volume discount, rounded half-up to the cent, and never below the floor. */
	static BigDecimal unitPrice(CatalogueItem item, BigDecimal quantity) {
		BigDecimal discount = DISCOUNTS.stream()
			.filter(d -> quantity.compareTo(d.atLeast()) >= 0)
			.map(Discount::off)
			.findFirst()
			.orElse(BigDecimal.ZERO);
		BigDecimal price = item.listPrice()
			.multiply(BigDecimal.ONE.subtract(discount))
			.setScale(2, RoundingMode.HALF_UP);
		return price.max(item.floorPrice().setScale(2));
	}

	/** The last check before an offer leaves this class; a bug above must fail loudly, not undercut the floor. */
	static void enforce(Offer offer) {
		if (offer.unitPrice().compareTo(offer.item().floorPrice()) < 0
				|| offer.quantity().compareTo(offer.item().stock()) > 0 || offer.quantity().signum() <= 0) {
			throw new IllegalStateException("Offer for " + offer.item().sku() + " breaks the floor or stock rule");
		}
	}

	private static Optional<String> malformed(RfqBody rfq) {
		if (rfq.product() == null || rfq.product().isBlank() || rfq.product().length() > 100) {
			return Optional.of("the product must be 1 to 100 characters");
		}
		if (rfq.origin() != null && rfq.origin().length() > 60) {
			return Optional.of("the origin must be at most 60 characters");
		}
		BigDecimal q = rfq.quantity();
		if (q == null || q.signum() <= 0 || q.compareTo(MAX_QUANTITY) > 0 || q.stripTrailingZeros().scale() > 3) {
			return Optional.of("the quantity must be more than 0 and at most 100000, with up to 3 decimals");
		}
		if (rfq.unit() == null) {
			return Optional.of("the unit must be kg or lb");
		}
		if (rfq.deliverBy() == null) {
			return Optional.of("deliverBy is required");
		}
		return Optional.empty();
	}

	/**
	 * Every word that says what the buyer wants must be in the item's product or description, so a qualifier such as
	 * "decaffeinated" is never dropped on the way to an ordinary lot. A word naming the item's origin ("Brazilian")
	 * counts as present. Filler words ("green", "fresh") don't count either way.
	 */
	static boolean sameProduct(String wanted, CatalogueItem item) {
		Set<String> required = words(wanted);
		Set<String> offered = words(item.product() + " " + item.description());
		required.removeIf(word -> offered.contains(word) || item.origin() != null && sameOrigin(word, item.origin()));
		return required.isEmpty() && !words(wanted).isEmpty();
	}

	/** No origin on either side matches anything; otherwise "Brazilian" matches "Brazil". */
	static boolean sameOrigin(String wanted, String offered) {
		if (wanted == null || wanted.isBlank() || offered == null) {
			return true;
		}
		String a = wanted.strip().toLowerCase(Locale.ROOT);
		String b = offered.strip().toLowerCase(Locale.ROOT);
		String stemA = a.substring(0, Math.min(5, a.length()));
		String stemB = b.substring(0, Math.min(5, b.length()));
		return a.equals(b) || a.startsWith(stemB) || b.startsWith(stemA);
	}

	private static Set<String> words(String text) {
		return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
			.map(word -> word.endsWith("s") && word.length() > 3 ? word.substring(0, word.length() - 1) : word)
			.filter(word -> word.length() >= 3 && !FILLER.contains(word))
			.collect(Collectors.toSet());
	}

	public sealed interface Decision permits Offer, Refusal {
	}

	/**
	 * @param quantity what this desk offers: at most the requested quantity and at most the stock
	 * @param available the stock, shown to the buyer
	 * @param partial true when the offer is less than requested
	 */
	public record Offer(CatalogueItem item, BigDecimal unitPrice, BigDecimal quantity, BigDecimal available,
			LocalDate deliveryDate, boolean partial) implements Decision {
	}

	public record Refusal(String reason) implements Decision {
	}

	record Discount(BigDecimal atLeast, BigDecimal off) {
	}

}
