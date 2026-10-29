package com.tradedesk.desk.paypal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The parts of PayPal Invoicing v2 we send and read, as typed records. PayPal's responses are parsed into these and
 * nothing else; unknown fields are ignored.
 */
public final class PayPalInvoicing {

	private PayPalInvoicing() {
	}

	/** What the desk asks PayPal to invoice. Amounts are already priced by the desk's own rules. */
	public record Draft(String invoiceNumber, String invoicerName, String customerName, String customerEmail,
			String currency, LocalDate dueDate, List<Item> items) {
	}

	public record Item(String name, BigDecimal quantity, BigDecimal unitPrice) {
	}

	/** A PayPal invoice as read back from the API. */
	public record Invoice(String id, String status, Detail detail, Amount amount, List<Link> links) {

		public String invoiceNumber() {
			return detail == null ? null : detail.invoiceNumber();
		}

		public LocalDate dueDate() {
			return detail == null || detail.paymentTerm() == null || detail.paymentTerm().dueDate() == null ? null
					: LocalDate.parse(detail.paymentTerm().dueDate());
		}

		/** The page the buyer pays on. */
		public String payerViewUrl() {
			return detail == null || detail.metadata() == null ? null : detail.metadata().recipientViewUrl();
		}

	}

	public record Detail(@JsonProperty("invoice_number") String invoiceNumber,
			@JsonProperty("currency_code") String currencyCode,
			@JsonProperty("payment_term") PaymentTerm paymentTerm, Metadata metadata) {
	}

	public record PaymentTerm(@JsonProperty("term_type") String termType,
			@JsonProperty("due_date") String dueDate) {
	}

	public record Metadata(@JsonProperty("recipient_view_url") String recipientViewUrl) {
	}

	public record Amount(@JsonProperty("currency_code") String currencyCode, String value) {
	}

	public record Link(String href, String rel, String method) {
	}

	// Request bodies, in PayPal's snake_case.

	record CreateBody(DetailBody detail, Invoicer invoicer,
			@JsonProperty("primary_recipients") List<Recipient> primaryRecipients, List<ItemBody> items,
			Configuration configuration) {

		static CreateBody of(Draft draft) {
			return new CreateBody(
					new DetailBody(draft.invoiceNumber(), draft.currency(),
							new PaymentTerm("DUE_ON_DATE_SPECIFIED", draft.dueDate().toString())),
					new Invoicer(draft.invoicerName()),
					List.of(new Recipient(new BillingInfo(draft.customerName(), draft.customerEmail()))),
					draft.items()
						.stream()
						.map(item -> new ItemBody(item.name(), item.quantity().toPlainString(),
								new Amount(draft.currency(), item.unitPrice().toPlainString()), "QUANTITY"))
						.toList(),
					new Configuration(false));
		}

	}

	record DetailBody(@JsonProperty("invoice_number") String invoiceNumber,
			@JsonProperty("currency_code") String currencyCode,
			@JsonProperty("payment_term") PaymentTerm paymentTerm) {
	}

	record Invoicer(@JsonProperty("business_name") String businessName) {
	}

	record Recipient(@JsonProperty("billing_info") BillingInfo billingInfo) {
	}

	record BillingInfo(@JsonProperty("business_name") String businessName,
			@JsonProperty("email_address") String emailAddress) {
	}

	record ItemBody(String name, String quantity, @JsonProperty("unit_amount") Amount unitAmount,
			@JsonProperty("unit_of_measure") String unitOfMeasure) {
	}

	record Configuration(@JsonProperty("allow_tip") boolean allowTip) {
	}

	record SendBody(@JsonProperty("send_to_invoicer") boolean sendToInvoicer,
			@JsonProperty("send_to_recipient") boolean sendToRecipient) {
	}

	record NextNumber(@JsonProperty("invoice_number") String invoiceNumber) {
	}

	record Token(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {

		@Override
		public String toString() {
			return "Token[expiresIn=" + expiresIn + "]";
		}

	}

	/** PayPal's error body; token errors use {@code error}, API errors use {@code name} and {@code details}. */
	record Error(String name, String message, @JsonProperty("debug_id") String debugId, List<ErrorDetail> details,
			String error, @JsonProperty("error_description") String errorDescription) {
	}

	record ErrorDetail(String issue, String description) {
	}

}
