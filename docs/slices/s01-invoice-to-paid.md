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
| `POST /api/invoices` | Create and send. Body: `{ "customer": { "name", "email" }, "lines": [{ "description", "quantity", "unitPrice": Money }] }` |
| `GET /api/events` | SSE, emits `invoice.updated` with the ledger row |

PayPal calls (sandbox base URL constant):

| Step | Call |
| --- | --- |
| Token | `POST /v1/oauth2/token` (client credentials) |
| Invoice number | `POST /v2/invoicing/generate-next-invoice-number` |
| Create draft | `POST /v2/invoicing/invoices` with a `PayPal-Request-Id` header for idempotency |
| Send | `POST /v2/invoicing/invoices/{id}/send`; keep the payer-view link it returns |
| Poll | `GET /v2/invoicing/invoices/{id}`, read `status` |

Our status enum maps PayPal's: `DRAFT`, `SENT`, `PAID`, `PARTIALLY_PAID`, `UNPAID`, `PAYMENT_PENDING`, `CANCELLED`, plus `OTHER` for anything unexpected (log it, don't crash).

## Data

Table `invoice`: `id`, `paypal_invoice_id` (unique), `invoice_number`, `customer_name`, `customer_email`, `total_minor` (bigint, cents), `currency`, `status`, `payer_view_url`, `due_date` (a date, read from PayPal's `detail.payment_term.due_date`; we create invoices with a net-14 term in the roaster's configured time zone), `source` (`MANUAL` now; `NEGOTIATION`, `MESSY_ORDER` later), `created_at`, `updated_at`, `paid_at`.

Table `invoice_line`: `invoice_id`, `description`, `quantity`, `unit_price_minor`.

## Acceptance criteria

1. From the roaster UI, creating a 2-line invoice to the sandbox buyer email shows a new row with status SENT within 5 seconds.
2. The same invoice is visible in the roaster's sandbox merchant account at sandbox.paypal.com.
3. Opening the row's PayPal link and paying with the sandbox buyer account flips the row to PAID in under 10 seconds, with no refresh.
4. Restarting the roaster container keeps the ledger (Postgres) and the poller picks up open invoices again.
5. Totals are computed in Java with `BigDecimal` and match PayPal's total to the cent; an invoice with a non-positive quantity or price is rejected with 400 before any PayPal call.
6. Wrong PayPal credentials show a readable error in the UI, not a stack trace, and nothing secret is logged.
7. Unit tests cover the total calculation and the status mapping; one integration test stubs PayPal with WireMock.

## Review-sensitive

- Polling interval and whether to add webhooks for the hosted demo.
- Whether invoices should go through PayPal's MCP server instead of our own REST client. Current decision: our REST client for the core flow, MCP only as a stretch in S9.
