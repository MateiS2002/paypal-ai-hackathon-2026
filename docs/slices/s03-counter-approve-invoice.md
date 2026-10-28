# S3: Counter, approve, invoice

**Owner:** Matei · **Estimate:** 1.5 days · **Depends on:** S1, S2

## Goal

Close the loop from need to money. The buyer agent sends one counter-offer to the top 2 or 3 quotes, recommends a winner with a one-line reason, the Java policy engine decides whether the owner must approve, and on accept the supplier desk creates and sends a PayPal invoice through S1's `InvoiceService`. The café sees it under "Invoices to pay".

Together, S1 + S2 + S3 are the "vertical slice" in architecture.md's working order.

## Demo moment

Counters go out, two suppliers improve their price, the agent says "Roaster: $1 more per kg than C but arrives 4 days sooner, and you asked for speed". The total is $640, above the $500 limit, so an "Approve" button appears. One tap, and the invoice shows up on the café side and in the roaster's ledger.

## In scope

- **Counter round:** exactly one. Buyer agent proposes a target price per supplier; Java clamps it (never above the current quote, never above `maxUnitPrice`). Supplier desks accept, partly meet, or hold, decided in Java against their floor.
- **Recommendation:** LLM writes the reason; Java picks the winner by the same ranking as S2, so the reason can't pick a different supplier than the code.
- **Policy engine (plain Java):** café rules `maxUnitPrice`, `approvalAbove` (e.g. $500), `maxOrderTotal`. Outcomes: `AUTO_ACCEPT`, `NEEDS_APPROVAL`, `BLOCKED` with reasons. Café owner can edit the rules on a settings screen.
- **Approval:** `approval.needed` SSE event, one-tap Approve or Reject in the UI. Nothing is sent to the supplier before approval.
- **Accept to invoice:** café sends `accept`; supplier desk re-checks the quote is still valid and within floor, then calls `InvoiceService` with `source = NEGOTIATION` and returns the PayPal invoice id and payer link in its reply.
- Café UI: "Invoices to pay" list with the PayPal pay link and live status. The supplier desk's S1 poller sends an `invoice.status` message to the café whenever the status changes; the café updates `payable.status` from it and emits `invoice.updated` on its own SSE stream.
- Simulated suppliers invoice through the shared SIMULATED sandbox merchant.

## Out of scope

Multiple counter rounds, partial deliveries, the malicious supplier (S8).

## Contracts

Agent message types added: `counter` (`{ "quoteId", "unitPrice": Money }`), `quote` (revised), `accept` (`{ "quoteId" }`), reply `invoice` (`{ "paypalInvoiceId", "payerViewUrl", "total": Money, "status" }`), `reject`, and `invoice.status` from supplier to buyer (`{ "paypalInvoiceId", "status", "updatedAt" }`). `status` uses S1's enum. The café ignores an `invoice.status` for an invoice id it doesn't hold or from a desk that isn't that invoice's supplier.

Desk API (café): `POST /api/needs/{id}/counter`, `GET /api/needs/{id}/recommendation`, `POST /api/approvals/{id}` with `{ "decision": "APPROVE" | "REJECT" }`, `GET /api/payables`, `GET/PUT /api/policy`.

## Data

Café: `policy` (single row of limits, money in minor units), `approval` (need_id, quote_id, reasons, status, decided_at), `payable` (supplier, paypal_invoice_id, payer_view_url, total_minor, status).

Supplier: `invoice.source = NEGOTIATION` plus `conversation_id` to link back to the quote.

## Acceptance criteria

1. Full run from typed need to a PAID invoice works end to end in under 2 minutes on screen, with real sandbox calls for the roaster.
2. An order of $499 auto-accepts; $501 waits for approval; a quote above `maxUnitPrice` is `BLOCKED` with the reason shown. Unit-tested in the policy engine, no LLM involved.
3. Nothing reaches a supplier between "needs approval" and the tap.
4. A supplier never accepts a counter below its floor (unit test).
5. Accepting an expired quote returns `reject` with a reason; no invoice is created.
6. The recommended supplier is always the one Java ranked first (test with a stubbed LLM that argues for another).

## Review-sensitive

- Whether the agent counters automatically or the owner presses "counter". Default: automatic, shown as it happens.
- Policy rule set: are `maxUnitPrice`, `approvalAbove` and `maxOrderTotal` enough, or does the owner need per-supplier limits?
