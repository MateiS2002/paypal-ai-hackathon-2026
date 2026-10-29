package com.tradedesk.desk.invoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Our invoice status. Mostly PayPal's, folded to the states the ledger shows; {@link #AWAITING_APPROVAL} and
 * {@link #OTHER} are ours.
 */
public enum InvoiceStatus {

	DRAFT, SENT, PAID, PARTIALLY_PAID, UNPAID, PAYMENT_PENDING, CANCELLED, REFUNDED,

	/** Held for the owner's approval with no PayPal call yet (S7 messy orders above the approval limit). */
	AWAITING_APPROVAL,

	/** A PayPal status we don't know. Logged, shown as is, and still polled. */
	OTHER;

	private static final Logger log = LoggerFactory.getLogger(InvoiceStatus.class);

	/** Maps PayPal's {@code status} field. Never throws: unknown values become {@link #OTHER}. */
	public static InvoiceStatus fromPayPal(String status) {
		if (status == null) {
			log.warn("PayPal invoice has no status");
			return OTHER;
		}
		return switch (status) {
			case "DRAFT" -> DRAFT;
			case "SENT", "SCHEDULED" -> SENT;
			case "PAID", "MARKED_AS_PAID", "PAID_EXTERNAL" -> PAID;
			case "PARTIALLY_PAID" -> PARTIALLY_PAID;
			case "UNPAID" -> UNPAID;
			case "PAYMENT_PENDING" -> PAYMENT_PENDING;
			case "CANCELLED", "AUTO_CANCELLED" -> CANCELLED;
			case "REFUNDED", "MARKED_AS_REFUNDED", "PARTIALLY_REFUNDED" -> REFUNDED;
			default -> {
				// The value comes from outside; keep it to one short, printable token in the log.
				String printable = status.replaceAll("[^A-Za-z0-9_]", "?");
				log.warn("Unknown PayPal invoice status '{}', showing it as OTHER",
						printable.substring(0, Math.min(printable.length(), 40)));
				yield OTHER;
			}
		};
	}

	/** Statuses the poller keeps checking, because PayPal can still move them on. */
	public boolean isOpen() {
		return switch (this) {
			case SENT, PARTIALLY_PAID, UNPAID, PAYMENT_PENDING, OTHER -> true;
			default -> false;
		};
	}

}
