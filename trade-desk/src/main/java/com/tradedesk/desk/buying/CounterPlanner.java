package com.tradedesk.desk.buying;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.llm.Llm;
import com.tradedesk.desk.llm.LlmException;
import com.tradedesk.desk.llm.LlmNotConfiguredException;
import com.tradedesk.desk.need.PurchaseRequest;
import com.tradedesk.desk.need.QuoteRow;
import com.tradedesk.desk.policy.Policy;
import com.tradedesk.desk.policy.PolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * The buyer agent's counter targets (S3). The LLM proposes one target price per quote; Java parses the answer as
 * untrusted input and {@link #clamp clamps} each target: never above the quote's price, never above the policy's
 * {@code maxUnitPrice} or the need's own budget, and never more than {@link #MAX_ASK} below the quote (a sanity bound
 * on what the café asks, not a money rule). Without an LLM, or if its answer doesn't fit, Java asks for
 * {@link #DEFAULT_ASK} off.
 *
 * <p>The prompt names quotes S1, S2, S3 and carries only Java's numbers: no supplier name or note, which are text a
 * supplier wrote and could use to steer the café's agent.
 */
@Component
public class CounterPlanner {

	/** What the café asks for without an LLM: 5% off. */
	static final BigDecimal DEFAULT_ASK = new BigDecimal("0.05");

	/** The café never asks for more than 15% off in one counter. */
	static final BigDecimal MAX_ASK = new BigDecimal("0.15");

	static final String SCHEMA_NAME = "counter_targets";

	static final String SCHEMA = """
			{
			  "type": "object",
			  "additionalProperties": false,
			  "required": ["targets"],
			  "properties": {
			    "targets": {
			      "type": "array",
			      "items": {
			        "type": "object",
			        "additionalProperties": false,
			        "required": ["label", "unitPrice"],
			        "properties": {
			          "label": { "type": "string", "description": "The quote's label, e.g. S1." },
			          "unitPrice": { "type": "string", "description": "Target price per unit as a plain decimal number with at most 2 decimals, e.g. 10.85." }
			        }
			      }
			    }
			  }
			}
			""";

	private static final Logger log = LoggerFactory.getLogger(CounterPlanner.class);

	private final JsonMapper json = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.build();

	private final Llm llm;

	public CounterPlanner(Llm llm) {
		this.llm = llm;
	}

	/**
	 * @param quotes the quotes to counter, best first
	 * @return quote id to clamped target, only for quotes where the target is below the current price
	 */
	public Map<UUID, BigDecimal> plan(PurchaseRequest request, List<QuoteRow> quotes, Policy policy) {
		Map<String, String> proposed = propose(request, quotes);
		Map<UUID, BigDecimal> targets = new LinkedHashMap<>();
		for (int i = 0; i < quotes.size(); i++) {
			QuoteRow quote = quotes.get(i);
			BigDecimal price = quote.unitPrice().amount();
			BigDecimal wanted = decimal(proposed.get(label(i)));
			if (wanted == null) {
				wanted = price.multiply(BigDecimal.ONE.subtract(DEFAULT_ASK));
			}
			BigDecimal target = clamp(wanted, quote.unitPrice(), PolicyEngine.maxUnitPrice(policy, quote.unit()),
					request.maxUnitPrice());
			if (target.signum() > 0 && target.compareTo(price) < 0) {
				targets.put(quote.id(), target);
			}
		}
		return targets;
	}

	/**
	 * The money rule for a counter target, in Java: rounded down to the cent, at least {@link #MAX_ASK} off at most,
	 * then never above the quote, the policy's maximum or the need's budget (the hard limits win over the lower bound).
	 */
	static BigDecimal clamp(BigDecimal wanted, Money quoted, Money policyMax, Money needMax) {
		BigDecimal price = quoted.amount();
		BigDecimal lowest = price.multiply(BigDecimal.ONE.subtract(MAX_ASK)).setScale(2, RoundingMode.CEILING);
		BigDecimal target = wanted.setScale(2, RoundingMode.DOWN).max(lowest).min(price);
		for (Money cap : new Money[] { policyMax, needMax }) {
			if (cap != null && cap.currency().equals(quoted.currency())) {
				target = target.min(cap.amount().setScale(2, RoundingMode.DOWN));
			}
		}
		return target;
	}

	/** The LLM's proposals by label, or none if there is no LLM or its answer doesn't fit. Untrusted strings. */
	private Map<String, String> propose(PurchaseRequest request, List<QuoteRow> quotes) {
		if (!llm.configured() || quotes.isEmpty()) {
			return Map.of();
		}
		try {
			Answer answer = json.readValue(llm.json(SYSTEM, facts(request, quotes), SCHEMA_NAME, SCHEMA), Answer.class);
			Map<String, String> byLabel = new HashMap<>();
			if (answer != null && answer.targets() != null) {
				answer.targets().stream()
					.filter(t -> t != null && t.label() != null && t.unitPrice() != null)
					.forEach(t -> byLabel.putIfAbsent(t.label().strip(), t.unitPrice()));
			}
			return byLabel;
		}
		catch (LlmException | LlmNotConfiguredException | JacksonException ex) {
			log.info("Counter targets fall back to {}% off: {}", DEFAULT_ASK.movePointRight(2).stripTrailingZeros()
				.toPlainString(), ex.getClass().getSimpleName());
			return Map.of();
		}
	}

	private static final String SYSTEM = """
			You are a café's purchasing agent. For each supplier quote, propose one counter-offer price per unit that \
			the supplier might accept: a modest discount, usually 3 to 8 percent below its price. Ask for more off \
			the quotes that are expensive compared with the others, less off the cheap ones. Never propose a price \
			above the quote's own price or above the café's budget. Use only the numbers given. Answer with the JSON \
			schema only.
			""";

	/** Only Java's numbers, under labels; nothing a supplier wrote. */
	static String facts(PurchaseRequest request, List<QuoteRow> quotes) {
		StringBuilder facts = new StringBuilder()
			.append("The café needs ").append(request.quantity().stripTrailingZeros().toPlainString()).append(' ')
			.append(request.unit().wireName()).append(", delivered by ").append(request.deliverBy())
			.append(". Its priority is ").append(request.priority().name().toLowerCase(Locale.ROOT)).append('.');
		if (request.maxUnitPrice() != null) {
			facts.append(" Its budget is ").append(request.maxUnitPrice().amount().toPlainString()).append(' ')
				.append(request.maxUnitPrice().currency()).append(" per ").append(request.unit().wireName()).append('.');
		}
		facts.append("\nQuotes:");
		for (int i = 0; i < quotes.size(); i++) {
			QuoteRow q = quotes.get(i);
			facts.append("\n").append(label(i)).append(": ").append(q.unitPrice().amount().toPlainString()).append(' ')
				.append(q.unitPrice().currency()).append(" per ").append(q.unit().wireName()).append(", ")
				.append(q.quantity().stripTrailingZeros().toPlainString()).append(' ').append(q.unit().wireName())
				.append(q.partial() ? " (less than needed)" : "").append(", delivered ").append(q.deliveryDate())
				.append('.');
		}
		return facts.toString();
	}

	static String label(int index) {
		return "S" + (index + 1);
	}

	/** A plain decimal with at most 2 places and more than 0, or null. */
	private static BigDecimal decimal(String value) {
		if (value == null || !value.strip().matches("\\d{1,7}(\\.\\d{1,2})?")) {
			return null;
		}
		BigDecimal amount = new BigDecimal(value.strip());
		return amount.signum() > 0 ? amount : null;
	}

	/** The LLM's answer, exactly as the schema allows. Untrusted. */
	record Answer(List<Target> targets) {
	}

	record Target(String label, String unitPrice) {
	}

}
