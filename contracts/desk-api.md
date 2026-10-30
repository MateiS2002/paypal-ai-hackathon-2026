# Desk API (frontend to desk)

Every desk (café, roaster, simulated suppliers) is the same `trade-desk` app and exposes the same routes for its own UI. The browser reaches them through the frontend's nginx on one origin, so CORS stays closed:

| Browser calls | nginx forwards to |
| --- | --- |
| `/cafe/api/**` | café desk `/api/**` |
| `/roaster/api/**` | roaster desk `/api/**` |

`/actuator/**` is never proxied. Each slice adds its routes and event types to the tables below in the same PR.

## Conventions

- **Auth:** form login with a session cookie. Each desk names its cookie `<deskId>_SESSION` (for example `roaster_SESSION`) because all desks share the `localhost:8080` origin.
- **CSRF** is on. Get the token from `GET /api/csrf` and send it in the header it names (`X-CSRF-TOKEN`) on every `POST`, `PUT`, `PATCH` and `DELETE`, including login and logout. Fetch it again after login, because login rotates it.
- **Errors:** unauthenticated calls get `401` with no redirect, so the React app shows its own login screen.
- **Money** is always `{"amount": "12.50", "currency": "USD"}` ([money.schema.json](money.schema.json)).
- **Times** are ISO 8601 UTC strings; the UI converts to the desk's time zone.

## Routes

| Method and path | Auth | Slice | What it does |
| --- | --- | --- | --- |
| `GET /api/csrf` | none | S0 | `{"headerName": "X-CSRF-TOKEN", "token": "..."}` for this session. |
| `POST /api/login` | none (CSRF) | S0 | Form fields `username`, `password`. `204` on success, `401` on bad credentials. The username is the desk id (`cafe`, `roaster`); the password is `DESK_DEMO_PASSWORD`. |
| `POST /api/logout` | session | S0 | `204`. |
| `GET /api/me` | session | S0 | `{"username", "deskId", "deskName", "domain", "simulated"}`. `401` before login. |
| `GET /api/events` | session | S0 | Server-sent events, see below. |
| `GET /api/invoices` | session | S1 | Ledger rows, newest first: an array of [invoice rows](#invoice-row). |
| `POST /api/invoices` | session | S1 | Create a PayPal sandbox invoice and send it. See [Creating an invoice](#creating-an-invoice). |
| `GET /api/customers` | session | S1 | Known customers for the new-invoice form: `[{"name", "email"}]`. The roaster seeds Bean There with `PAYPAL_SANDBOX_BUYER_EMAIL`. |
| `POST /api/needs/parse` | session | S2 | `{"text"}` to a [parse result](#parse-result). No side effects. |
| `POST /api/needs` | session | S2 | A confirmed [purchase request](#purchase-request); stores the need and sends an RFQ to every supplier. See [Posting a need](#posting-a-need). |
| `GET /api/needs` | session | S2 | This desk's needs, newest first: an array of [need rows](#need-row). |
| `GET /api/needs/{id}/quotes` | session | S2 | Every quote for the need that passed the desk's checks, ranked best first: an array of [quote rows](#quote-row). Quotes past their `validUntil` are included, the same as in `quote.received`; malformed replies never are. `404` for an unknown need. |
| `POST /api/needs/{id}/counter` | session | S3 | Starts the need's one counter round if it hasn't started (the desk also starts it by itself once every supplier has answered). No body. `202` with the [recommendation](#recommendation) when this call started it, `200` with it when it had already started. `409` while the need is still collecting quotes or has no quote to counter. |
| `GET /api/needs/{id}/recommendation` | session | S3 | The need's [recommendation](#recommendation). `404` for an unknown need. |
| `POST /api/approvals/{id}` | session | S3 | `{"decision": "APPROVE" \| "REJECT"}` on a `PENDING` [approval](#approval). See [Approving](#approving). |
| `GET /api/payables` | session | S3 | Invoices this desk has to pay, newest first: an array of [payable rows](#payable-row). |
| `GET /api/policy` | session | S3 | The desk's [policy](#policy): the limits its policy engine checks. |
| `PUT /api/policy` | session | S3 | Replaces the policy. `200` with the stored policy, `400` if a rule below is broken. Applies to every decision made after it, including an approval tapped later. |

## Events (`GET /api/events`)

One SSE stream per desk. Each event's `data` is JSON shaped by [sse-event.schema.json](sse-event.schema.json): `{"type": "...", "data": {...}}`. Events have no SSE `event:` name, so `EventSource.onmessage` receives all of them. The server sends a comment line every 25 seconds as a heartbeat.

| Type | Slice | Data |
| --- | --- | --- |
| `desk.connected` | S0 | `{"deskId": "roaster"}`, sent once when the stream opens. |
| `invoice.updated` | S1 | An [invoice row](#invoice-row), sent when an invoice is created or its PayPal status changes. The UI replaces the row with the same `id`. Example: [sse-invoice-updated.json](examples/sse-invoice-updated.json). |
| `need.updated` | S2 | A [need row](#need-row), sent when a need is posted and when every supplier has answered or timed out. |
| `quote.received` | S2 | `{"needId", "quotes": [...]}`: the need's whole ranked list of [quote rows](#quote-row), sent each time a valid quote arrives. A new quote can change the rank of earlier ones, so the UI replaces the list. A need's list only ever grows: a supplier quotes at most once per need, and neither the events nor `GET /api/needs/{id}/quotes` ever drop a quote, including after its `validUntil` (each row carries `validUntil`; the UI may mark it expired, and S3 refuses to accept an expired quote). So when a GET response and an event race, the UI keeps whichever list has more quotes; an older snapshot can never remove a newer quote. Example: [sse-quote-received.json](examples/sse-quote-received.json). From S3 it is also sent when a counter goes out and when its answer arrives: the list keeps its length, but rows change price, `counter` and rank. So for lists of equal length the UI keeps the one from the event, and reloads with `GET /api/needs/{id}/quotes` after it reconnects. |
| `recommendation.updated` | S3 | A [recommendation](#recommendation), sent each time its `stage`, `approval` or order changes. The UI replaces the need's recommendation. Example: [sse-recommendation-updated.json](examples/sse-recommendation-updated.json). |
| `approval.needed` | S3 | An [approval](#approval) in `PENDING`: the owner must tap Approve or Reject. Sent once, when the policy engine decides `NEEDS_APPROVAL`. Example: [sse-approval-needed.json](examples/sse-approval-needed.json). |
| `payable.updated` | S3 | A [payable row](#payable-row), sent when an invoice arrives from a supplier and when its supplier reports a new status. The UI replaces the row with the same `id`. Buyer desks only; a payable is not a ledger row, so it has its own type rather than `invoice.updated`. Example: [sse-payable-updated.json](examples/sse-payable-updated.json). |

## Errors

Errors other than `401` and `403` have an RFC 9457 problem body: `{"title": "...", "status": 400, "detail": "..."}`. `detail` is safe to show to the user as is; it never contains a stack trace or a secret.

## Invoices (S1)

### Invoice row

```json
{
  "id": "6f1c2e8a-3b0d-4c55-9a51-0c6f3c1d2b7e",
  "invoiceNumber": "0042",
  "customer": { "name": "Bean There", "email": "buyer@example.com" },
  "lines": [
    { "description": "Santos natural, kg", "quantity": "0.333", "unitPrice": { "amount": "18.00", "currency": "USD" }, "lineTotal": { "amount": "5.99", "currency": "USD" } }
  ],
  "total": { "amount": "5.99", "currency": "USD" },
  "status": "SENT",
  "source": "MANUAL",
  "dueDate": "2026-11-12",
  "payerViewUrl": "https://www.sandbox.paypal.com/invoice/p/#...",
  "createdAt": "2026-10-29T18:00:00Z",
  "updatedAt": "2026-10-29T18:00:02Z",
  "paidAt": null
}
```

- `quantity` is a decimal string (up to 3 decimal places), like money amounts.
- `status` is one of `DRAFT`, `SENT`, `PAID`, `PARTIALLY_PAID`, `UNPAID`, `PAYMENT_PENDING`, `CANCELLED`, `REFUNDED`, `AWAITING_APPROVAL`, `OTHER`.
- `source` is one of `MANUAL`, `NEGOTIATION`, `MESSY_ORDER`.
- `invoiceNumber`, `payerViewUrl` and `dueDate` are `null` until PayPal has the invoice (for example while `AWAITING_APPROVAL`).
- Each line total is `quantity × unitPrice` rounded half-up to the cent; `total` is the sum of the rounded line totals.

### Creating an invoice

`POST /api/invoices` with an `Idempotency-Key` header (a UUID the client generates once per invoice and reuses on retries) and this body:

```json
{
  "customer": { "name": "Bean There", "email": "buyer@example.com" },
  "lines": [
    { "description": "Santos natural, kg", "quantity": "0.333", "unitPrice": { "amount": "18.00", "currency": "USD" } }
  ]
}
```

| Status | When |
| --- | --- |
| `201` | Created and sent. The body is the invoice row. |
| `200` | A retry with the same key and body: the stored invoice row, with no new PayPal invoice. |
| `400` | Missing `Idempotency-Key`, no lines, a non-positive quantity or price, mixed currencies, or a bad email. Nothing is sent to PayPal. |
| `409` | The same `Idempotency-Key` was used with a different body. |
| `502` | PayPal refused or failed (for example wrong sandbox credentials). Retrying with the same key resumes where it stopped. |
| `503` | This desk has no PayPal credentials configured. |

## Needs and quotes (S2)

The café posts a need in plain words. Its buyer agent turns the text into a purchase request, the owner checks it, and the desk sends an RFQ to every supplier ([agent-messages.md](agent-messages.md)). Quotes are checked and ranked in Java; the LLM never sets a number.

### Purchase request

```json
{
  "product": "green coffee beans",
  "origin": "Brazil",
  "quantity": "50",
  "unit": "kg",
  "deliverBy": "2026-11-07",
  "priority": "SPEED",
  "maxUnitPrice": null
}
```

| Field | Rule |
| --- | --- |
| `product` | 1 to 100 characters. |
| `origin` | Up to 60 characters, or `null` for any origin. |
| `quantity` | Decimal string, greater than 0, at most 100000, up to 3 decimal places. |
| `unit` | `kg` or `lb`. |
| `deliverBy` | A date after today in the desk's time zone. |
| `priority` | `SPEED`, `PRICE` or `BALANCED`. Decides the ranking; never sent to suppliers. |
| `maxUnitPrice` | Money or `null`. The café's budget per unit; never sent to suppliers. In S2 the currency must be `USD`. |

### Parse result

`POST /api/needs/parse` with `{"text": "some beans"}` (1 to 1000 characters) answers `200` with:

```json
{
  "complete": false,
  "request": { "product": "green coffee beans", "origin": null, "quantity": null, "unit": null, "deliverBy": null, "priority": "BALANCED", "maxUnitPrice": null },
  "missing": ["quantity", "unit", "deliverBy"],
  "question": "How much (in kg or lb) and by when?"
}
```

- `request` has the same fields as a purchase request, but any of them may be `null`. For the demo text `50 kg Brazilian beans by Friday, prioritise speed` it is complete: Brazil, `50` `kg`, the coming Friday's date (relative days are resolved from today in the desk's time zone), `SPEED`.
- `missing` lists the fields the owner must still fill in. The desk never guesses a quantity, a unit, a date or a budget that isn't in the text: Java drops any the text doesn't state. A missing `priority` defaults to `BALANCED` and is not listed.
- `product` is the buyer agent's wording, which Java only trims and length-checks. The agent's instructions tell it to read "beans" on their own as `green coffee beans`, the product suppliers match against; Java doesn't enforce that mapping. The owner sees and can edit the product on the request card before anything is sent, and a product no supplier sells gets refused, never a wrong quote.
- `question` is written by the desk's code from `missing`, not by the LLM. It is `null` when `complete` is `true`.
- `complete` is `true` only when `request` passes every rule above, so the UI can post it unchanged.

| Status | When |
| --- | --- |
| `200` | Parsed, complete or not. |
| `400` | Empty or too long text. |
| `502` | The LLM failed or answered something that isn't valid JSON for the schema. Nothing is stored. |
| `503` | This desk has no OpenAI key configured. |

### Posting a need

`POST /api/needs` with an `Idempotency-Key` header (a UUID per need, reused on retries) and a purchase request as the body.

| Status | When |
| --- | --- |
| `201` | Stored; RFQs are being sent. The body is the need row. Quotes arrive as `quote.received` events. |
| `200` | A retry with the same key and body: the stored need row, and no second round of RFQs. |
| `400` | Missing `Idempotency-Key`, or the request breaks a rule above. Nothing is sent. |
| `409` | The same `Idempotency-Key` was used with a different body. |

### Need row

```json
{
  "id": "3c2b1a09-8f7e-4d6c-9b5a-4e3d2c1b0a9f",
  "request": { "product": "green coffee beans", "origin": "Brazil", "quantity": "50", "unit": "kg", "deliverBy": "2026-11-07", "priority": "SPEED", "maxUnitPrice": null },
  "status": "COLLECTING",
  "suppliersAsked": 4,
  "quotesReceived": 1,
  "createdAt": "2026-11-03T10:00:00Z"
}
```

`status` is `COLLECTING` while RFQs are out and `COMPLETE` once every supplier has answered, refused or timed out.

### Quote row

```json
{
  "id": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "needId": "3c2b1a09-8f7e-4d6c-9b5a-4e3d2c1b0a9f",
  "rank": 1,
  "supplier": { "domain": "roaster.test", "name": "Roaster & Co", "simulated": false },
  "unitPrice": { "amount": "11.40", "currency": "USD" },
  "quantity": "50",
  "availableQuantity": "320",
  "unit": "kg",
  "total": { "amount": "570.00", "currency": "USD" },
  "deliveryDate": "2026-11-05",
  "validUntil": "2026-11-04T10:00:00Z",
  "partial": false,
  "note": "Fresh Cerrado lot, ships from Oakland in two days.",
  "receivedAt": "2026-11-03T10:00:01Z"
}
```

- `total` is `quantity × unitPrice` rounded half-up to the cent, computed by the café.
- `partial` is `true` when the supplier offers less than the requested quantity.
- `rank` starts at 1. Full offers always rank above partial ones; within each group:
  - `PRICE`: lowest unit price first, then earliest delivery.
  - `SPEED`: earliest delivery first, then lowest unit price.
  - `BALANCED`: lowest score first, where score = half the unit price's position between the cheapest and dearest quote (0 to 1) plus half the delivery date's position between the earliest and latest (0 to 1); ties by unit price. A position is 0 when every quote has the same value (so a single quote, or quotes that share a price or a date, still get a score).
  - Any remaining tie is broken by `supplier.domain`, so the order never depends on arrival time.
- `supplier.name` and `supplier.simulated` are what the supplier claimed in its quote (verified from S4).
- `note` is display text written by the supplier's LLM; the café never reads numbers from it.
- `counter` (S3) is `null` until the need's counter round reaches this quote, then `{"targetUnitPrice": Money, "previousUnitPrice": Money, "outcome"}`. `outcome` is `PENDING` while the counter is out, then `MET`, `PARTLY_MET`, `HELD` or `NO_ANSWER` (unreachable, timed out, or a revised quote the café dropped). Once the answer is in, `unitPrice`, `total`, `validUntil` and `note` are the revised quote's and `rank` is recomputed; `previousUnitPrice` keeps the price before the counter.

## Counter, approve, invoice (S3)

Once every supplier has answered the RFQ (the need is `COMPLETE`), the café's buyer agent counters its top 3 quotes once ([agent-messages.md](agent-messages.md#negotiation-s3)). The counter is automatic and shown as it happens: each quote row's `counter` moves from `PENDING` to its outcome in `quote.received` events. Then Java ranks the revised quotes with the S2 ranking, picks rank 1 as the winner, and the policy engine decides:

| Decision | What happens |
| --- | --- |
| `AUTO_ACCEPT` | The café sends `accept` at once. |
| `NEEDS_APPROVAL` | `approval.needed`; nothing reaches the supplier until the owner taps Approve. |
| `BLOCKED` | Nothing is ever sent. The reasons are shown; the owner can change the policy and post the need again. |

The LLM proposes counter targets and writes the one-line reason, nothing else. Java clamps the targets, picks the winner and makes the decision; a reason that argues for another supplier is replaced by a template.

### Recommendation

```json
{
  "needId": "3c2b1a09-8f7e-4d6c-9b5a-4e3d2c1b0a9f",
  "stage": "READY",
  "pick": { "...": "a quote row" },
  "reason": "Roaster & Co: $1.90 more per kg than Bulk Bean Traders but arrives 7 days sooner, and you asked for speed.",
  "reasonSource": "AI",
  "approval": { "...": "an approval" }
}
```

| Field | Rule |
| --- | --- |
| `stage` | `WAITING_FOR_QUOTES` while the need is `COLLECTING`, `READY_TO_COUNTER` once it is `COMPLETE` but no round has started, `COUNTERING` while counters are out, `READY` once there is a pick, `NO_OFFER` when no quote was left to pick (none arrived, all expired, or none answered its counter). |
| `pick` | The [quote row](#quote-row) Java ranked first among quotes still within `validUntil` whose counter didn't end in `NO_ANSWER` (the supplier may have replaced such a quote, so it may no longer be acceptable), or `null` before `READY`. |
| `reason` | One line, at most 200 characters, display text only. `null` before `READY`. |
| `reasonSource` | `AI` when the buyer agent wrote the reason and it passed the checks, `TEMPLATE` when the desk's code wrote it (no LLM, a failed call, or an answer that named another supplier). |
| `approval` | The need's [approval](#approval), or `null` before `READY`. |

### Approval

```json
{
  "id": "5e4d3c2b-1a09-4f8e-8d7c-6b5a4f3e2d1c",
  "needId": "3c2b1a09-8f7e-4d6c-9b5a-4e3d2c1b0a9f",
  "quoteId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "supplier": { "domain": "roaster.test", "name": "Roaster & Co", "simulated": false },
  "total": { "amount": "541.50", "currency": "USD" },
  "decision": "NEEDS_APPROVAL",
  "reasons": ["The total 541.50 USD is above your approval limit of 500.00 USD."],
  "status": "PENDING",
  "order": "NOT_SENT",
  "orderDetail": null,
  "createdAt": "2026-11-03T10:00:20Z",
  "decidedAt": null
}
```

| Field | Rule |
| --- | --- |
| `quoteId` | The café's quote row id (not the supplier's message id). |
| `decision` | The policy engine's outcome: `AUTO_ACCEPT`, `NEEDS_APPROVAL` or `BLOCKED`. |
| `reasons` | Plain sentences written by the policy engine, at least one. |
| `status` | `AUTO_APPROVED`, `PENDING`, `APPROVED`, `REJECTED` or `BLOCKED`. |
| `order` | `NOT_SENT` until an `accept` goes out, then `SENDING`, then `INVOICED` (a payable exists), `REFUSED` (the supplier answered `reject`) or `FAILED` (no answer, or an `invoice` the café refused). |
| `orderDetail` | Why the order was refused or failed, safe to show; otherwise `null`. |

### Approving

`POST /api/approvals/{id}` with `{"decision": "APPROVE"}` or `{"decision": "REJECT"}`.

| Status | When |
| --- | --- |
| `200` | Decided. The body is the approval. On `APPROVE` the policy engine checks the order again with the current policy and time first; `order` is then `SENDING` and the result arrives as `recommendation.updated` and `payable.updated` events. |
| `400` | A missing or unknown `decision`. |
| `404` | Unknown approval. |
| `409` | Not `PENDING` (already decided, or a double tap), or on `APPROVE` the policy engine now says `BLOCKED` (for example the quote expired or the limits changed). The approval is then stored as `BLOCKED` with the new reasons, and nothing is sent. |

### Payable row

```json
{
  "id": "7c6b5a49-3827-4e16-9d5c-4b3a29180f7e",
  "needId": "3c2b1a09-8f7e-4d6c-9b5a-4e3d2c1b0a9f",
  "supplier": { "domain": "roaster.test", "name": "Roaster & Co", "simulated": false },
  "description": "50 kg green coffee beans from Brazil",
  "paypalInvoiceId": "INV2-ABCD-1234-EFGH-5678",
  "payerViewUrl": "https://www.sandbox.paypal.com/invoice/p/#INV2-ABCD-1234-EFGH-5678",
  "total": { "amount": "541.50", "currency": "USD" },
  "status": "SENT",
  "createdAt": "2026-11-03T10:00:31Z",
  "updatedAt": "2026-11-03T10:00:31Z"
}
```

- `status` is S1's [invoice status](#invoice-row), as last reported by the supplier in `invoice` or `invoice.status`.
- `payerViewUrl` is always a `https://www.sandbox.paypal.com/` link; the café refuses an invoice with any other.
- `description` is written by the café from its own need, not taken from the supplier.
- The café can't read another merchant's PayPal invoice, so it can't check the amount behind `payerViewUrl` itself. It checks the `invoice` reply's `total` against the accepted quote, and the UI shows that agreed total and `paypalInvoiceId` next to the pay link, so the owner can compare them with PayPal's page before paying. S5's signed envelopes tie the reply to the supplier.

### Policy

```json
{
  "maxUnitPrice": { "amount": "15.00", "currency": "USD" },
  "approvalAbove": { "amount": "500.00", "currency": "USD" },
  "maxOrderTotal": { "amount": "2000.00", "currency": "USD" },
  "updatedAt": "2026-11-03T09:00:00Z"
}
```

Example: [policy.json](examples/policy.json). The body of `PUT /api/policy` is the same without `updatedAt`. Every amount is in USD, more than 0, in whole cents and at most 1000000.00. `maxUnitPrice` is **per kg**: for a need in `lb` the engine converts the quote's price to per kg (1 lb = 0.45359237 kg) before comparing, and caps counter targets at the same limit per lb. The need's own `maxUnitPrice` is in the need's unit. The policy engine is plain Java and checks every order before the café sends `accept`, in this order:

| Rule | Outcome |
| --- | --- |
| The quote's currency isn't the policy's | `BLOCKED` |
| The total isn't `quantity × unitPrice`, rounded half-up to the cent | `BLOCKED` |
| The quote is past its `validUntil` | `BLOCKED` |
| The unit price is above `maxUnitPrice`, or above the need's own `maxUnitPrice` | `BLOCKED` |
| The total is above `maxOrderTotal` | `BLOCKED` |
| The total is above `approvalAbove` | `NEEDS_APPROVAL` |
| The offer is for less than the need's quantity (a partial offer) | `NEEDS_APPROVAL` |
| Otherwise | `AUTO_ACCEPT` |

Every rule that applies adds a reason, and the strictest outcome wins. "Above" is strict: with `approvalAbove` at 500.00, a 500.00 order auto-accepts and a 500.01 order waits. The defaults (shown above) apply until the owner changes them.
