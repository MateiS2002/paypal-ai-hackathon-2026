package com.tradedesk.desk.invoice;

/** Where an invoice came from. Only {@link #MESSY_ORDER} invoices can be held for approval. */
public enum InvoiceSource {

	/** Typed by the owner in the new-invoice form (S1). */
	MANUAL,

	/** Agreed in a negotiation the café already approved (S3). */
	NEGOTIATION,

	/** Read from a pasted email or PDF by the seller agent (S7). */
	MESSY_ORDER

}
