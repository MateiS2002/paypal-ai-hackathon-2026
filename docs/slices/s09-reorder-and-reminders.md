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

- Overdue = status `SENT` or `UNPAID` and due date in the past. A demo-only "pretend it's a week later" switch shifts the desk clock, labelled as a demo control.
- Roaster agent drafts a short, polite note (LLM); Java decides who gets one (at most one reminder per invoice per 3 days).
- Send through **PayPal's MCP server** (`@paypal/mcp` or `mcp.sandbox.paypal.com`) with Spring AI's MCP client and a tool allowlist of `send_invoice_reminder` and `list_invoices` only. Fallback if MCP gives trouble: `POST /v2/invoicing/invoices/{id}/remind` from our own client.

## Out of scope

Real inventory integrations, scheduled reorders, escalating reminder schedules.

## Contracts

Café: `GET /api/inventory`, `POST /api/inventory/{sku}/usage`, rules inside `GET/PUT /api/policy`. Roaster: `POST /api/invoices/{id}/remind`, `GET /api/invoices?overdue=true`, SSE `invoice.reminded`.

## Data

Café: `inventory_item` (sku, description, stock_qty, unit), `reorder_rule`. Roaster: `invoice.due_date`, `invoice.last_reminded_at`.

## Acceptance criteria

1. Logging usage below the threshold starts exactly one need; logging again doesn't start a second while one is open.
2. The overdue invoice gets a reminder visible in the sandbox; a second press within 3 days is refused by Java.
3. The MCP client can't call any PayPal tool outside the allowlist (test).
4. If the MCP server is unreachable the reminder still goes out through REST, and the UI says which path was used.

## Review-sensitive

- Whether MCP is worth the extra moving part. It's the only place we use PayPal's Agent Toolkit, which judges may look for under "Best Use of Agentic Commerce".
