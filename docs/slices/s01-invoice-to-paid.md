# S1: Invoice to paid

**Owner:** Matei · **Estimate:** 2 days · **Depends on:** S0

## Goal

The roaster creates an invoice for a café, a real PayPal sandbox invoice is created and sent, the café pays it on PayPal's hosted sandbox page, and the roaster's AG Grid ledger shows the row change from SENT to PAID without a page refresh.

This is the first vertical slice. Every later money flow (S3 negotiation, S7 messy orders, S9 reminders) ends by calling the `InvoiceService` built here.

## Demo moment

Split screen: roaster ledger on the left, PayPal sandbox pay page on the right. Pay, and within a few seconds the ledger row turns green and says PAID.

## In scope

- Roaster UI: AG Grid ledger (invoice number, customer, items, total, status, due date, PayPal link) and a small "New invoice" form (customer, one or more lines of product, quantity, unit price).
- `InvoiceService` in `trade-desk`: create, send and read invoices through PayPal **Invoicing v2 REST** with Spring `RestClient` (the Java Server SDK has no invoicing).
- OAuth client-credentials token from `/v1/oauth2/token`, cached until shortly before expiry.
- A poller that checks open invoices every 5 seconds and publishes `invoice.updated` on the SSE stream when the status changes. Webhooks are off locally (they can't reach `localhost`).
- Café side for this slice: the pay link is opened directly in the browser with the sandbox buyer account. The café's own "Invoices to pay" screen comes in S3.

## Out of scope

Agents, LLM calls, negotiation, PDF parsing, reminders, webhooks.

## Contracts

Desk API (roaster):

| Route | What it does |
| --- | --- |
| `GET /api/invoices` | Ledger rows |
| `POST /api/invoices` | Create and send. Needs an `Idempotency-Key` header (client-generated UUID, kept across browser retries). Body: `{ "customer": { "name", "email" }, "lines": [{ "description", "quantity", "unitPrice": Money }] }`. The route takes no `sourceText` and always sets `source = MANUAL`; only `InvoiceService`'s Java method accepts the optional `sourceText`, which S7 sets |
| `GET /api/events` | SSE, emits `invoice.updated` with the ledger row |

PayPal calls (sandbox base URL constant):

| Step | Call |
| --- | --- |
| Token | `POST /v1/oauth2/token` (client credentials) |
| Invoice number | `POST /v2/invoicing/generate-next-invoice-number` |
| Create draft | `POST /v2/invoicing/invoices` with a `PayPal-Request-Id` header for idempotency |
| Send | `POST /v2/invoicing/invoices/{id}/send`; keep the payer-view link it returns |
| Poll | `GET /v2/invoicing/invoices/{id}`, read `status` |

Our status enum maps PayPal's: `DRAFT`, `SENT`, `PAID`, `PARTIALLY_PAID`, `UNPAID`, `PAYMENT_PENDING`, `CANCELLED`, `REFUNDED`, with these explicit mappings: `SCHEDULED` → `SENT`, `MARKED_AS_PAID` and `PAID_EXTERNAL` → `PAID`, `AUTO_CANCELLED` → `CANCELLED`, `REFUNDED`, `MARKED_AS_REFUNDED` and `PARTIALLY_REFUNDED` → `REFUNDED`. Two more values are ours, not PayPal's: `AWAITING_APPROVAL` (S1's `InvoiceService` supports it: for `source = MESSY_ORDER` only, a total above `DESK_INVOICE_APPROVAL_ABOVE` is saved without any PayPal call until the approve route in S7 releases it; `MANUAL` invoices typed by the owner and `NEGOTIATION` invoices, already approved on the café side, are never held) and `OTHER` for anything unexpected (log it, don't crash).

## Data

Table `invoice`: `id`, `paypal_invoice_id` (unique), `invoice_number`, `customer_name`, `customer_email`, `total_minor` (bigint, cents), `currency`, `status`, `payer_view_url`, `due_date` (a date, read from PayPal's `detail.payment_term.due_date`; we create invoices with `detail.payment_term.term_type = DUE_ON_DATE_SPECIFIED` and an explicit `detail.payment_term.due_date` of today + 14 days, counted in the desk's `DESK_TIME_ZONE` from S0, because PayPal has no NET_14 term), `source` (`MANUAL` now; `NEGOTIATION`, `MESSY_ORDER` later; the column is a plain text/enum with all three values and `source_text` exists from the S1 Flyway migration, so S7 needs no schema change for them), `source_text` (nullable; the original order text, set only for `MESSY_ORDER`), `created_at`, `updated_at`, `paid_at`.

Table `customer`: `id`, `name`, `email` (unique); created here in S1's migration (seeded with the demo's Bean There) and reused by S7. The new-invoice form finds or creates the customer by email.

Table `invoice_line`: `invoice_id`, `description`, `quantity` (decimal(10,3), positive), `unit_price_minor`, `line_total_minor`.

Rounding: each line total is `quantity × unit price` rounded `HALF_UP` to the cent, and `total_minor` is the sum of the rounded line totals. The same rounded values go into the PayPal payload (unit amount and quantity per line), so our total and PayPal's agree to the cent.

Idempotency: the first call stores an `invoice_operation` row (`idempotency_key` unique, `request_hash`, `paypal_request_id`, `step` = CREATING / CREATED / SENT, `invoice_id`) before any PayPal call. A repeat with the same key and body returns the stored result or resumes from `step` (send only, if the draft exists), always reusing the same `paypal_request_id`; the same key with a different body is 409. A browser double-click or timeout therefore never creates a second PayPal invoice.

## Acceptance criteria

1. From the roaster UI, creating a 2-line invoice to the sandbox buyer email shows a new row with status SENT within 5 seconds.
2. The same invoice is visible in the roaster's sandbox merchant account at sandbox.paypal.com.
3. Opening the row's PayPal link and paying with the sandbox buyer account flips the row to PAID in under 10 seconds, with no refresh.
4. Restarting the roaster container keeps the ledger (Postgres) and the poller picks up open invoices again.
5. Totals are computed in Java with `BigDecimal` using the rounding rule above (a 0.333 kg line is tested) and match PayPal's total to the cent; an invoice with a non-positive quantity or price is rejected with 400 before any PayPal call.
6. Wrong PayPal credentials show a readable error in the UI, not a stack trace, and nothing secret is logged.
7. Two identical POSTs with the same `Idempotency-Key` produce one PayPal invoice; a crash between create and send resumes with the send (test with WireMock).
8. Unit tests cover the total calculation and the status mapping; one integration test stubs PayPal with WireMock.

## Review-sensitive

- Polling interval and whether to add webhooks for the hosted demo.
- Whether invoices should go through PayPal's MCP server instead of our own REST client. Current decision: our REST client for the core flow, MCP only as a stretch in S9.
