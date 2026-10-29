package com.tradedesk.desk.paypal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * PayPal Invoicing v2 over REST (the PayPal Java Server SDK has no invoicing). Only {@code InvoiceService} calls
 * this; the LLM never gets a tool that reaches it, and nothing here logs a credential, a token or a request body.
 *
 * <p>The OAuth client-credentials token is cached until shortly before it expires. A 401 from the API drops the
 * cached token and retries the call once.
 */
@Component
public class PayPalClient {

	/** Sandbox only, by design: no setting or code path points this desk at live PayPal. */
	public static final String SANDBOX_BASE_URL = "https://api-m.sandbox.paypal.com";

	private static final Logger log = LoggerFactory.getLogger(PayPalClient.class);

	private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofSeconds(60);

	private final RestClient http;

	private final PayPalProperties properties;

	private final JsonMapper json;

	private final Clock clock;

	private String token;

	private Instant tokenExpiresAt = Instant.MIN;

	@Autowired
	public PayPalClient(PayPalProperties properties, JsonMapper json, Clock clock) {
		this(properties, json, clock, SANDBOX_BASE_URL);
	}

	/** For tests only: points the client at a local stub (WireMock) instead of the sandbox. */
	PayPalClient(PayPalProperties properties, JsonMapper json, Clock clock, String baseUrl) {
		this.properties = properties;
		this.json = json;
		this.clock = clock;
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(20));
		this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

	public boolean configured() {
		return properties.configured();
	}

	/** {@code POST /v2/invoicing/generate-next-invoice-number}. */
	public String nextInvoiceNumber() {
		PayPalInvoicing.NextNumber next = call("generate invoice number", token -> http.post()
			.uri("/v2/invoicing/generate-next-invoice-number")
			.headers(h -> h.setBearerAuth(token))
			// No body, but PayPal answers 415 without a JSON content type.
			.contentType(MediaType.APPLICATION_JSON)
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::fail)
			.body(PayPalInvoicing.NextNumber.class));
		if (next == null || next.invoiceNumber() == null) {
			throw new PayPalException("PayPal did not return an invoice number.", 200);
		}
		return next.invoiceNumber();
	}

	/**
	 * {@code POST /v2/invoicing/invoices}: creates a draft. The same {@code requestId} always yields the same PayPal
	 * invoice, so a retry after a timeout never creates a second one.
	 */
	public PayPalInvoicing.Invoice createDraft(String requestId, PayPalInvoicing.Draft draft) {
		PayPalInvoicing.Invoice created = call("create invoice", token -> http.post()
			.uri("/v2/invoicing/invoices")
			.headers(h -> {
				h.setBearerAuth(token);
				h.set("PayPal-Request-Id", requestId);
				h.set("Prefer", "return=representation");
			})
			.contentType(MediaType.APPLICATION_JSON)
			.body(PayPalInvoicing.CreateBody.of(draft))
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::fail)
			.body(PayPalInvoicing.Invoice.class));
		if (created == null) {
			throw new PayPalException("PayPal did not return the new invoice.", 201);
		}
		if (created.id() != null && created.status() != null) {
			return created;
		}
		// PayPal answered with links only: follow "self" to read the invoice.
		String self = links(created).stream()
			.filter(link -> "self".equals(link.rel()) && link.href() != null)
			.map(link -> link.href().substring(link.href().lastIndexOf('/') + 1))
			.findFirst()
			.orElseThrow(() -> new PayPalException("PayPal did not return the new invoice's id.", 201));
		return get(self);
	}

	/**
	 * {@code POST /v2/invoicing/invoices/{id}/send}: emails the invoice to the customer.
	 * @return the payer-view link PayPal returns, or {@code null} if it returned none
	 */
	public String send(String invoiceId, String requestId) {
		PayPalInvoicing.Link link = call("send invoice", token -> http.post()
			.uri("/v2/invoicing/invoices/{id}/send", invoiceId)
			.headers(h -> {
				h.setBearerAuth(token);
				h.set("PayPal-Request-Id", requestId);
			})
			.contentType(MediaType.APPLICATION_JSON)
			.body(new PayPalInvoicing.SendBody(false, true))
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::fail)
			.body(PayPalInvoicing.Link.class));
		return link == null ? null : link.href();
	}

	/** {@code GET /v2/invoicing/invoices/{id}}. */
	public PayPalInvoicing.Invoice get(String invoiceId) {
		PayPalInvoicing.Invoice invoice = call("read invoice", token -> http.get()
			.uri("/v2/invoicing/invoices/{id}", invoiceId)
			.headers(h -> h.setBearerAuth(token))
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::fail)
			.body(PayPalInvoicing.Invoice.class));
		if (invoice == null || invoice.id() == null) {
			throw new PayPalException("PayPal returned an empty invoice.", 200);
		}
		return invoice;
	}

	/** Runs one API call with a valid token; on a 401, gets a fresh token and tries once more. */
	private <T> T call(String what, Function<String, T> request) {
		if (!configured()) {
			throw new PayPalNotConfiguredException();
		}
		try {
			try {
				return request.apply(token());
			}
			catch (Unauthorized ex) {
				clearToken();
				return request.apply(token());
			}
		}
		catch (Unauthorized ex) {
			clearToken();
			log.warn("PayPal refused the access token for '{}' twice", what);
			throw credentialsRejected();
		}
		catch (ResourceAccessException ex) {
			log.warn("PayPal sandbox unreachable during '{}': {}", what, ex.getClass().getSimpleName());
			throw new PayPalException("Could not reach the PayPal sandbox. Check the internet connection and try "
					+ "again; retrying will not create a second invoice.", ex);
		}
	}

	private synchronized String token() {
		if (token != null && clock.instant().isBefore(tokenExpiresAt)) {
			return token;
		}
		PayPalInvoicing.Token fresh;
		try {
			fresh = http.post()
				.uri("/v1/oauth2/token")
				.headers(h -> h.setBasicAuth(properties.clientId(), properties.clientSecret()))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body("grant_type=client_credentials")
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					int status = response.getStatusCode().value();
					PayPalInvoicing.Error error = readError(response);
					log.warn("PayPal token request failed: HTTP {} {}", status,
							error == null ? "" : printable(error.error()));
					if (status == 400 || status == 401) {
						throw credentialsRejected();
					}
					throw new PayPalException("PayPal sandbox could not issue a token (HTTP " + status
							+ "). Try again in a moment.", status);
				})
				.body(PayPalInvoicing.Token.class);
		}
		catch (ResourceAccessException ex) {
			log.warn("PayPal sandbox unreachable during token request: {}", ex.getClass().getSimpleName());
			throw new PayPalException("Could not reach the PayPal sandbox. Check the internet connection and try "
					+ "again.", ex);
		}
		if (fresh == null || fresh.accessToken() == null) {
			throw new PayPalException("PayPal sandbox returned no access token.", 200);
		}
		token = fresh.accessToken();
		tokenExpiresAt = clock.instant().plusSeconds(fresh.expiresIn()).minus(REFRESH_BEFORE_EXPIRY);
		return token;
	}

	private synchronized void clearToken() {
		token = null;
		tokenExpiresAt = Instant.MIN;
	}

	/** Turns an error response into a message the owner can act on. Logs PayPal's error name and debug id only. */
	private void fail(org.springframework.http.HttpRequest request, ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		if (status == 401) {
			throw new Unauthorized();
		}
		PayPalInvoicing.Error error = readError(response);
		String name = error == null ? null : error.name();
		String debugId = error == null ? null : error.debugId();
		String issue = error == null || error.details() == null || error.details().isEmpty() ? null
				: error.details().getFirst().issue();
		log.warn("PayPal {} {} failed: HTTP {} {} {} debug_id={}", request.getMethod(), request.getURI().getPath(),
				status, printable(name), printable(issue), printable(debugId));
		if (status == 403) {
			throw new PayPalException("The PayPal sandbox app is not allowed to use Invoicing. Enable Invoicing for "
					+ "the REST app in the PayPal developer dashboard.", status);
		}
		if (status >= 500) {
			throw new PayPalException("The PayPal sandbox is having trouble (HTTP " + status + "). Try again; "
					+ "retrying will not create a second invoice.", status);
		}
		String reason = error == null ? null : firstNonBlank(
				error.details() == null || error.details().isEmpty() ? null : error.details().getFirst().description(),
				error.message());
		throw new PayPalException("PayPal refused the request" + (reason == null ? "" : ": " + reason)
				+ (debugId == null ? "" : " (PayPal debug id " + printable(debugId) + ")"), status);
	}

	private PayPalInvoicing.Error readError(ClientHttpResponse response) {
		try {
			return json.readValue(response.getBody(), PayPalInvoicing.Error.class);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static PayPalException credentialsRejected() {
		return new PayPalException("PayPal rejected this desk's sandbox credentials. Check PAYPAL_CLIENT_ID and "
				+ "PAYPAL_CLIENT_SECRET in .env (Sandbox, not Live) and restart the desk.", 401);
	}

	private static List<PayPalInvoicing.Link> links(PayPalInvoicing.Invoice invoice) {
		return invoice.links() == null ? List.of() : invoice.links();
	}

	private static String firstNonBlank(String first, String second) {
		return first != null && !first.isBlank() ? first : second;
	}

	/** Values from PayPal go into logs as one short line. */
	private static String printable(String value) {
		if (value == null) {
			return "";
		}
		String clean = value.replaceAll("[\\r\\n\\t]", " ");
		return clean.length() > 120 ? clean.substring(0, 120) : clean;
	}

	/** A 401 from an API call: the cached token is stale or the credentials changed. */
	private static final class Unauthorized extends RuntimeException {

		Unauthorized() {
			super(null, null, false, false);
		}

	}

	@Override
	public String toString() {
		return "PayPalClient[" + Objects.toString(properties) + "]";
	}

}
