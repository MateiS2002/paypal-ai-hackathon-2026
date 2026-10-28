# S9: Reorder trigger and late-payer reminder

**Owner:** either (decide on Nov 7) · **Estimate:** 1.5 days (0.5 reorder, 1 reminder) · **Depends on:** S2 (reorder), S1 (reminder)

## Goal

Two small autonomy moments from the demo flow: the café's agent starts a reorder by itself when stock falls below a rule, and the roaster's agent sends a polite PayPal reminder for an overdue invoice. The reminder is where we use PayPal's own Agent Toolkit through its MCP server.

## Demo moment

- Reorder: the café's stock card drops from 22 kg to 18 kg after "Log today's usage"; a banner says "Below your 20 kg rule, asking suppliers for 50 kg", and S2's quotes start arriving.
- Reminder: on the roaster's ledger, an invoice 8 days overdue gets a "Reminder sent" chip; the sandbox buyer sees the reminder from PayPal.

## In scope

**Reorder (0.5 day)**

- Café `inventory_item` (seeded stock), reorder rules (`keepAtLeast`, `reorderQuantity`, `priority`) on the policy screen, a "Log today's usage" button.
- When stock falls below the rule, Java builds the `PurchaseRequest` (no LLM needed) and starts the S2 flow. One open need per product at a time.

**Late-payer reminder (1 day)**

- Overdue = status `SENT` or `UNPAID` and due date before today in the desk's `DESK_TIME_ZONE`. A demo-only "pretend it's a week later" switch shifts the desk clock, labelled as a demo control.
- Roaster agent drafts a short, polite note (LLM, strict JSON schema `{ "subject", "note" }`); Java validates it (lengths within PayPal's limits, plain text, no links) and falls back to a fixed template if it fails. Java decides who gets one (at most one reminder per invoice per 3 days).
- Send through **PayPal's MCP server** (`@paypal/mcp` or `mcp.sandbox.paypal.com`) with Spring AI's MCP client and a tool allowlist of `send_invoice_reminder` and `list_invoices` only. The MCP call passes `invoice_id` plus the validated `subject` and `note` and nothing else (no `additional_recipients`), so the reminder only reaches the invoice's own recipient. Fallback: `POST /v2/invoicing/invoices/{id}/remind` from our own client with `send_to_invoicer: false`. Known limitation: we don't rely on the REST call carrying our subject and note, so a REST reminder may arrive with PayPal's default text; the UI says so when `deliveryPath` is `REST`. Each attempt stores a `PayPal-Request-Id`, reused on retries. Fall back only when the MCP call failed before it was sent (server unreachable); if the MCP result is ambiguous (timeout after sending), mark the reminder `UNKNOWN` and leave a retry to the owner, so nobody gets two.

## Out of scope

Real inventory integrations, scheduled reorders, escalating reminder schedules.

## Contracts

Café: `GET /api/inventory`, `POST /api/inventory/{sku}/usage`, rules inside `GET/PUT /api/policy`. Roaster: `POST /api/invoices/{id}/remind`, `GET /api/invoices?overdue=true`, SSE `invoice.reminded`, emitted only after a reminder was sent. Both the remind response and the event carry `deliveryPath`: `MCP` or `REST`.

## Data

Café: `inventory_item` (sku, description, stock_qty, unit), `reorder_rule`. Roaster: `invoice.due_date`, `invoice.last_reminded_at` (set only on `SENT`), and `reminder_attempt` (id, invoice_id, status `SENT` | `UNKNOWN` | `FAILED`, delivery_path `MCP` | `REST`, paypal_request_id, created_at). The 3-day rule counts `SENT` and `UNKNOWN` attempts, so an ambiguous one also blocks an automatic second send.

## Acceptance criteria

1. Logging usage below the threshold starts exactly one need; logging again doesn't start a second while one is open.
2. The overdue invoice gets a reminder visible in the sandbox; a second press within 3 days is refused by Java.
3. The MCP client can't call any PayPal tool outside the allowlist (test).
4. If the MCP server is unreachable the reminder still goes out through REST, and the UI says which path was used.

## Review-sensitive

- Whether MCP is worth the extra moving part. It's the only place we use PayPal's Agent Toolkit, which judges may look for under "Best Use of Agentic Commerce".
