package com.tradedesk.desk.need;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tradedesk.desk.contract.Money;
import com.tradedesk.desk.contract.Unit;

/**
 * One row of the café's offers grid: a checked quote plus its rank. Shape documented in contracts/desk-api.md.
 *
 * @param total {@code quantity × unitPrice} rounded half-up to the cent, computed by the café
 * @param counter null until the need's counter round reaches this quote (S3); after the answer, {@code unitPrice},
 *     {@code total}, {@code validUntil} and {@code note} are the revised quote's
 */
public record QuoteRow(UUID id, UUID needId, int rank, Supplier supplier, Money unitPrice,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableQuantity, Unit unit, Money total,
		LocalDate deliveryDate, Instant validUntil, boolean partial, String note, Instant receivedAt,
		Counter counter) {

	QuoteRow withRank(int rank) {
		return new QuoteRow(id, needId, rank, supplier, unitPrice, quantity, availableQuantity, unit, total,
				deliveryDate, validUntil, partial, note, receivedAt, counter);
	}

	/** Name and SIMULATED flag are what the supplier claimed in its quote (verified from S4). */
	public record Supplier(String domain, String name, boolean simulated) {
	}

	/**
	 * The café's one counter on this quote (S3).
	 * @param previousUnitPrice the quote's price before the counter
	 */
	public record Counter(Money targetUnitPrice, Money previousUnitPrice, Outcome outcome) {

		public enum Outcome {

			/** Sent, no answer yet. */
			PENDING,

			/** The supplier met the target. */
			MET,

			/** The supplier moved part of the way. */
			PARTLY_MET,

			/** The supplier kept its price, or refused the counter. */
			HELD,

			/** Unreachable, timed out, or a revised quote the café dropped as malformed. */
			NO_ANSWER

		}

	}

}
