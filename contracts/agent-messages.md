# Agent messages (desk to desk)

Every desk exposes `POST /agent/messages`. The request and the response are both an [envelope](envelope.schema.json); this file defines each type's `body`. Money is always [Money](money.schema.json), quantities are decimal strings with up to 3 decimal places, and dates are ISO 8601 (`2026-11-07`, or UTC date-times).

The receiver treats every envelope as untrusted input: it parses the body into a typed record, validates it, and drops or rejects anything that doesn't match. Until S5, `from` is only claimed, not proven (see [S5](../docs/slices/s05-tokens-and-fingerprints.md)).

## Transport

| Item | Rule |
| --- | --- |
| Call | `POST /agent/messages`, `Content-Type: application/json`, the envelope as the body. |
| Reply | The reply envelope travels in the HTTP response of the same call (synchronous; S2 decision). `from` and `to` are swapped, `conversationId` is the same, `messageId` is new. |
| Errors | `400` with a problem body for an envelope that fails to parse, is addressed to another desk, or has a type this desk doesn't accept. A well-formed request the desk won't serve gets a `reject` envelope with `200`, not an error. `503` with a problem body when the desk can't answer yet (S3: an `accept` whose PayPal call failed on PayPal's side); no reply is stored, so a retry with the same `messageId` is handled afresh. |
| Retries | A repeated `messageId` gets the reply that was stored for it the first time, so a retry never produces a second quote. |
| Storage | Both desks store their own copy of every envelope they send and receive (`conversation_message`). |
| Addresses | Until S4, the café finds suppliers in its static `DESK_PEERS` list ([README](README.md#environment-variables)). |
| Auth | None until S5, which adds a registry token with scope `rfq:send` for `rfq`. Until then the endpoint is unauthenticated and reachable from the compose network and from any local process through the desk's `127.0.0.1` port (8081 to 8085). Desks refuse browser requests (any `Origin` or `Sec-Fetch-Site` header gets `403`), so a web page can't post to it, but local programs can. |

## `rfq` (buyer to supplier, S2)

A request for a quote, sent to each supplier. It carries only what a supplier needs to price the request. **It never contains the buyer's budget (`maxUnitPrice`), its stock levels, or its priority**: a supplier that knows the café wants speed can raise its price, so ranking stays on the café's side.

```json
{ "product": "green coffee beans", "origin": "Brazil", "quantity": "50", "unit": "kg", "deliverBy": "2026-11-07" }
```

| Field | Type | Rule |
| --- | --- | --- |
| `product` | string | 1 to 100 characters. |
| `origin` | string or `null` | Country or region, up to 60 characters. `null` means any origin. |
| `quantity` | decimal string | Greater than 0, at most 100000, up to 3 decimal places. |
| `unit` | `kg` or `lb` | A supplier selling in another unit rejects (no conversion in S2). |
| `deliverBy` | date | The latest delivery date the buyer wants, in the buyer's time zone. |

Example: [envelope-rfq.json](examples/envelope-rfq.json).

## `quote` (supplier to buyer, reply to `rfq`, S2)

```json
{
  "rfqId": "6f1c2b7e-3d4a-4f8e-9b1a-2c3d4e5f6a7b",
  "supplierName": "Roaster & Co",
  "simulated": false,
  "unitPrice": { "amount": "11.40", "currency": "USD" },
  "quantity": "50",
  "availableQuantity": "320",
  "unit": "kg",
  "deliveryDate": "2026-11-05",
  "validUntil": "2026-11-04T10:00:00Z",
  "note": "Fresh Cerrado lot, ships from Oakland in two days."
}
```

| Field | Type | Rule |
| --- | --- | --- |
| `rfqId` | UUID | The `messageId` of the `rfq` this answers. |
| `supplierName` | string | Display name, 1 to 100 characters. Self-declared until S4 verifies the agent card. |
| `simulated` | boolean | `true` for SIMULATED suppliers; the café shows a SIMULATED badge. |
| `unitPrice` | Money | Greater than 0 and in whole cents (at most 2 decimal places, though Money allows 4), because quotes are stored in minor units; a finer price is dropped, never rounded. In S2 the currency must be `USD`. Set by the supplier's Java code, never by its LLM, and never below its floor price. |
| `quantity` | decimal string | What the supplier offers for this RFQ: greater than 0, at most the requested quantity. Less than requested means a partial offer. |
| `availableQuantity` | decimal string | The supplier's current stock in the same unit, 0 or more and at least `quantity`. Shown in the offers grid's stock column. |
| `unit` | `kg` or `lb` | The same unit as the RFQ. |
| `deliveryDate` | date | When the supplier can deliver. May be after `deliverBy`; the café ranks it lower. |
| `validUntil` | date-time | The quote expires after this; must be in the future when received. S3 rejects accepting an expired quote. |
| `note` | string | One line, at most 200 characters, written by the supplier's LLM. Display text only: the café never reads numbers from it. |

The café drops and logs a quote that breaks any rule above, comes from a desk it didn't send the RFQ to, or answers another RFQ. Example: [envelope-quote.json](examples/envelope-quote.json).

## `reject` (any reply that refuses, S2)

```json
{ "rfqId": "6f1c2b7e-3d4a-4f8e-9b1a-2c3d4e5f6a7b", "reason": "We don't sell green coffee beans from Kenya." }
```

| Field | Type | Rule |
| --- | --- | --- |
| `rfqId` | UUID | The `messageId` of the message this refuses: an `rfq`, or in S3 a `counter`, an `accept` or an `invoice.status`. The name is kept from S2 so the body stays one shape. |
| `reason` | string | 1 to 200 characters, safe to show to the owner as plain text. |

## Negotiation (S3)

After every supplier has answered the RFQ, refused it or timed out (the need is `COMPLETE`), the café sends **one** `counter` to each of its top 3 quotes (ranked as in [desk-api.md](desk-api.md#quote-row)), picks a winner in Java, and sends `accept` once its policy engine (and, above the approval limit, the owner) agrees. The supplier answers the accept with an `invoice` and later pushes `invoice.status` whenever PayPal reports a change. Every message in a negotiation uses the RFQ's `conversationId`.

| Request | From | Reply | Scope from S5 |
| --- | --- | --- | --- |
| `counter` | buyer | revised `quote`, or `reject` (the quote stands as it was) | `quote:counter` |
| `accept` | buyer | `invoice`, or `reject` (no invoice was created) | `quote:accept` |
| `invoice.status` | supplier | `invoice.status` (what the buyer recorded), or `reject` (ignored) | `invoice:notify` |

A `quote`, `reject` or `invoice` sent as a request, rather than as a reply, gets `400`. Until S5 every request above is unauthenticated, like `rfq`.

### `counter` (buyer to supplier)

```json
{ "quoteId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", "unitPrice": { "amount": "10.83", "currency": "USD" } }
```

| Field | Type | Rule |
| --- | --- | --- |
| `quoteId` | UUID | The `messageId` of the supplier's `quote` being countered. |
| `unitPrice` | Money | The buyer's target, in whole cents and the quote's currency, and lower than the quote's `unitPrice`. The buyer agent proposes it; the café's Java code rounds it down to the cent and clamps it to at most the café's `maxUnitPrice` (defined per kg, so for a quote in `lb` it is converted first: 1 lb = 0.45359237 kg) and the need's own budget (a `null` budget or café `maxUnitPrice` sets no cap). If the result isn't more than zero and strictly below the quote's `unitPrice`, no counter is sent for that quote. |

The supplier decides in Java, never in a prompt, and never goes below its floor price:

- **met:** the revised quote's `unitPrice` equals the target;
- **partly met:** it is between the target and the old price;
- **held:** it equals the old price, or the supplier answers `reject`.

There is exactly one counter per quote. A second `counter` for the same quote, or one for a quote that is expired, already accepted or not addressed to the sender, gets `reject`. A repeated `messageId` gets the stored reply, as always. Example: [envelope-counter.json](examples/envelope-counter.json).

### Revised `quote` (supplier to buyer, reply to `counter`)

The same body as an S2 [`quote`](#quote-supplier-to-buyer-reply-to-rfq-s2), plus one field:

| Field | Type | Rule |
| --- | --- | --- |
| `revises` | UUID | The `quoteId` of the counter. Absent or `null` on an S2 quote. |

`rfqId`, `supplierName`, `simulated`, `quantity`, `unit` and `deliveryDate` must equal the quote it revises; only `unitPrice`, `availableQuantity`, `validUntil` and `note` may change. `unitPrice` is at most the old price. The revised quote replaces the old one: from then on the buyer accepts the revised quote's `messageId`, and the supplier refuses to accept the old one. The café drops and logs a revised quote that breaks any of these rules. The supplier may already have replaced the old quote by then, so the café never picks a quote whose counter got a dropped answer or no answer at all (counter outcome `NO_ANSWER`); a `reject`, or a counter the café never sent because its clamped target is not positive or not strictly below the quote price, leaves the old quote standing. Example: [envelope-quote-revised.json](examples/envelope-quote-revised.json).

### `accept` (buyer to supplier)

```json
{
  "quoteId": "4d3c2b1a-0f9e-4d8c-8b7a-6f5e4d3c2b1a",
  "billTo": { "name": "Corner Café", "email": "buyer@example.com" }
}
```

| Field | Type | Rule |
| --- | --- | --- |
| `quoteId` | UUID | The `messageId` of the quote being accepted: the revised quote if there was one. |
| `billTo` | object | Who the PayPal invoice goes to: `name` 1 to 100 characters, `email` a valid address. The café sends its own name and `PAYPAL_SANDBOX_BUYER_EMAIL`. It only reaches the supplier it accepts. |

The supplier re-checks, in Java, that the quote is its own latest quote for this buyer, still within `validUntil`, still at or above its floor price, and still in stock. If all hold it creates and sends a PayPal invoice with `source = NEGOTIATION`, linked to the `conversationId`, and answers `invoice`. Otherwise it answers `reject` with the reason (for example an expired quote) and creates no invoice. If PayPal is unreachable, rate-limited or failing on its side, the supplier answers `503` instead of `reject`, so the buyer can retry; the invoice's progress is stored, so the retry resumes the same invoice. Accepting the same quote again, with any `messageId`, returns the same invoice: one quote never gets two invoices. Examples: [envelope-accept.json](examples/envelope-accept.json), and the refusal of a different accept that arrived after its quote had expired, [envelope-reject-expired.json](examples/envelope-reject-expired.json).

### `invoice` (supplier to buyer, reply to `accept`)

```json
{
  "paypalInvoiceId": "INV2-ABCD-1234-EFGH-5678",
  "payerViewUrl": "https://www.sandbox.paypal.com/invoice/p/#INV2-ABCD-1234-EFGH-5678",
  "total": { "amount": "541.50", "currency": "USD" },
  "status": "SENT"
}
```

| Field | Type | Rule |
| --- | --- | --- |
| `paypalInvoiceId` | string | PayPal's invoice id, 1 to 100 characters of letters, digits and `-`. |
| `payerViewUrl` | URL | PayPal's hosted pay page. Must be `https://www.sandbox.paypal.com/...` and name `paypalInvoiceId` exactly, as the URL fragment of `/invoice/p/` (`/invoice/p/#INV2-...`) or the last segment of `/invoice/payerView/details/`, and no other path; the café refuses any other link, so a supplier can't send the owner to a look-alike page or another invoice's page. |
| `total` | Money | Must equal the accepted quote's `quantity × unitPrice`, rounded half-up to the cent, in the quote's currency. The café refuses an invoice with any other total. |
| `status` | string | S1's status enum (`DRAFT`, `SENT`, `PAID`, `PARTIALLY_PAID`, `UNPAID`, `PAYMENT_PENDING`, `CANCELLED`, `REFUNDED`, `AWAITING_APPROVAL`, `OTHER`); `SENT` when the invoice has just been sent. |

Example: [envelope-invoice.json](examples/envelope-invoice.json).

### `invoice.status` (supplier to buyer)

```json
{ "paypalInvoiceId": "INV2-ABCD-1234-EFGH-5678", "status": "PAID", "updatedAt": "2026-11-03T10:04:12Z" }
```

| Field | Type | Rule |
| --- | --- | --- |
| `paypalInvoiceId` | string | As in `invoice`. |
| `status` | string | S1's status enum. |
| `updatedAt` | date-time | When the supplier saw the change. The buyer ignores a status that isn't strictly newer than the one it holds (an equal `updatedAt` counts as stale), so messages that arrive out of order can't move an invoice back. |

The supplier's invoice poller sends this whenever a `NEGOTIATION` invoice's status changes, and keeps retrying until the buyer has answered. It only sends to buyer desks it knows (`DESK_BUYERS`, [README](README.md#environment-variables)), never to an address taken from a message. The buyer ignores (answers `reject`) a status for an invoice id it doesn't hold, or from a desk that isn't that invoice's supplier. Until S5, `from` is only claimed, so this message is unauthenticated: S5's `invoice:notify` scope and envelope signature close that gap. Example: [envelope-invoice-status.json](examples/envelope-invoice-status.json).

## Later slices

S5 adds the envelope `signature`, defined in the [S5](../docs/slices/s05-tokens-and-fingerprints.md) spec; it moves into this file when that slice lands.
