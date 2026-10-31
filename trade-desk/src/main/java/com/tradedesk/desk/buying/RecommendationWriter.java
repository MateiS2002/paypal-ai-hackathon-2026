package com.tradedesk.desk.buying;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.llm.Llm;
import com.tradedesk.desk.llm.LlmException;
import com.tradedesk.desk.llm.LlmNotConfiguredException;
import com.tradedesk.desk.need.Priority;
import com.tradedesk.desk.need.PurchaseRequest;
import com.tradedesk.desk.need.QuoteRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * The one-line reason for the recommendation (S3). Java has already picked the winner with S2's ranking; the LLM only
 * explains it. Its answer is untrusted: it must name Java's pick, start with that label and fit in one line, or it is
 * discarded and {@link #template} writes the reason instead. So the reason can never argue for a different supplier
 * than the one the café will accept.
 *
 * <p>The prompt calls suppliers S1, S2, ... and Java puts the names back afterwards: a supplier's name is text it chose,
 * and never reaches the café's LLM.
 */
@Component
public class RecommendationWriter {

	static final int MAX_REASON = 200;

	static final String SCHEMA_NAME = "recommendation_reason";

	static final String SCHEMA = """
			{
			  "type": "object",
			  "additionalProperties": false,
			  "required": ["pick", "reason"],
			  "properties": {
			    "pick": { "type": "string", "description": "The label of the recommended supplier, e.g. S1." },
			    "reason": { "type": "string", "description": "One sentence under 25 words that starts with the label and a colon, e.g. 'S1: ...'." }
			  }
			}
			""";

	private static final Pattern LABEL = Pattern.compile("\\bS(\\d{1,2})\\b");

	private static final Logger log = LoggerFactory.getLogger(RecommendationWriter.class);

	private final JsonMapper json = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.build();

	private final Llm llm;

	public RecommendationWriter(Llm llm) {
		this.llm = llm;
	}

	/**
	 * @param ranked the quotes that can still be accepted, ranked by Java; the first is the pick
	 */
	public Reason write(PurchaseRequest request, List<QuoteRow> ranked) {
		String template = template(request.priority(), ranked);
		if (!llm.configured()) {
			return new Reason(template, Reason.Source.TEMPLATE);
		}
		try {
			Answer answer = json.readValue(llm.json(SYSTEM, facts(request, ranked), SCHEMA_NAME, SCHEMA), Answer.class);
			String checked = check(answer, ranked);
			if (checked != null) {
				return new Reason(checked, Reason.Source.AI);
			}
			log.info("Discarded the buyer agent's reason: it didn't name the supplier Java ranked first");
		}
		catch (LlmException | LlmNotConfiguredException | JacksonException ex) {
			log.info("Recommendation reason falls back to the template: {}", ex.getClass().getSimpleName());
		}
		return new Reason(template, Reason.Source.TEMPLATE);
	}

	/**
	 * The LLM's reason with the names put back, or null if it argues for anyone but S1 (Java's pick), uses a label
	 * that doesn't exist, or doesn't fit in one line.
	 */
	static String check(Answer answer, List<QuoteRow> ranked) {
		if (answer == null || answer.pick() == null || answer.reason() == null
				|| !"S1".equals(answer.pick().strip())) {
			return null;
		}
		String line = answer.reason().strip().replaceAll("\\p{Cntrl}", " ");
		if (!line.startsWith("S1:") || line.length() > MAX_REASON) {
			return null;
		}
		Matcher labels = LABEL.matcher(line);
		StringBuilder named = new StringBuilder();
		while (labels.find()) {
			int index = Integer.parseInt(labels.group(1)) - 1;
			if (index < 0 || index >= ranked.size()) {
				return null;
			}
			labels.appendReplacement(named, Matcher.quoteReplacement(ranked.get(index).supplier().name()));
		}
		labels.appendTail(named);
		return cap(named.toString());
	}

	/** Java's own reason: the pick against the runner-up, in the terms of the café's priority. */
	static String template(Priority priority, List<QuoteRow> ranked) {
		QuoteRow pick = ranked.getFirst();
		String unit = pick.unit().wireName();
		if (ranked.size() == 1) {
			return cap(pick.supplier().name() + ": the only offer still open, " + money(pick.total()) + " for "
					+ pick.quantity().stripTrailingZeros().toPlainString() + " " + unit + ", delivered "
					+ pick.deliveryDate() + ".");
		}
		QuoteRow other = ranked.get(1);
		BigDecimal diff = pick.unitPrice().amount().subtract(other.unitPrice().amount());
		long sooner = ChronoUnit.DAYS.between(pick.deliveryDate(), other.deliveryDate());
		String name = other.supplier().name();
		String currency = pick.unitPrice().currency();
		String price = diff.signum() > 0 ? money(new Money(diff, currency)) + " more per " + unit + " than " + name
				: diff.signum() < 0 ? money(new Money(diff.negate(), currency)) + " less per " + unit + " than " + name
						: "the same price per " + unit + " as " + name;
		String time = sooner > 0 ? "arrives " + days(sooner) + " sooner"
				: sooner < 0 ? "arrives " + days(-sooner) + " later" : "arrives the same day";
		String joiner = diff.signum() * Long.signum(sooner) > 0 ? " but " : " and ";
		String why = switch (priority) {
			case SPEED -> ", and you asked for speed.";
			case PRICE -> ", and you asked for the best price.";
			case BALANCED -> ": the best mix of price and speed.";
		};
		String partial = other.partial() && !pick.partial() ? " " + name + " can't supply it all." : "";
		return cap(pick.supplier().name() + ": " + price + joiner + time + why + partial);
	}

	private static final String SYSTEM = """
			You explain a café's purchasing decision in one short sentence (under 25 words). The café's code has \
			already ranked the quotes and picked S1; explain why S1 is the best choice for the café's priority, \
			compared with the next best. Start the sentence with "S1:". Refer to suppliers only by their labels \
			(S1, S2, ...). Use only the numbers given. Plain text, no emojis. Answer with the JSON schema only.
			""";

	/** Only Java's numbers, under labels; nothing a supplier wrote. */
	static String facts(PurchaseRequest request, List<QuoteRow> ranked) {
		StringBuilder facts = new StringBuilder()
			.append("The café needs ").append(request.quantity().stripTrailingZeros().toPlainString()).append(' ')
			.append(request.unit().wireName()).append(" by ").append(request.deliverBy()).append(". Its priority is ")
			.append(request.priority().name().toLowerCase(Locale.ROOT)).append(".\nRanked quotes, best first:");
		for (int i = 0; i < ranked.size(); i++) {
			QuoteRow q = ranked.get(i);
			facts.append("\nS").append(i + 1).append(": ").append(q.unitPrice().amount().toPlainString()).append(' ')
				.append(q.unitPrice().currency()).append(" per ").append(q.unit().wireName()).append(", total ")
				.append(q.total().amount().toPlainString()).append(' ').append(q.total().currency())
				.append(" for ").append(q.quantity().stripTrailingZeros().toPlainString()).append(' ')
				.append(q.unit().wireName()).append(q.partial() ? " (less than needed)" : "").append(", delivered ")
				.append(q.deliveryDate()).append('.');
		}
		return facts.toString();
	}

	private static String days(long days) {
		return days == 1 ? "1 day" : days + " days";
	}

	/** {@code $1.90} for USD, {@code 1.90 EUR} otherwise. */
	static String money(Money money) {
		String amount = money.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
		return "USD".equals(money.currency()) ? "$" + amount : amount + " " + money.currency();
	}

	private static String cap(String text) {
		return text.length() > MAX_REASON ? text.substring(0, MAX_REASON - 1).strip() + "…" : text;
	}

	/** The LLM's answer, exactly as the schema allows. Untrusted. */
	record Answer(String pick, String reason) {
	}

	/** A reason for the owner, and who wrote it. */
	public record Reason(String text, Source source) {

		public enum Source {

			/** The buyer agent wrote it, and it passed the checks. */
			AI,

			/** The desk's code wrote it. */
			TEMPLATE

		}

	}

}
