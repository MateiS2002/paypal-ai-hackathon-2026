# Agent messages (desk to desk)

Every desk exposes `POST /agent/messages`. The request and the response are both an [envelope](envelope.schema.json); this file defines each type's `body`. Money is always [Money](money.schema.json), quantities are decimal strings with up to 3 decimal places, and dates are ISO 8601 (`2026-11-07`, or UTC date-times).

The receiver treats every envelope as untrusted input: it parses the body into a typed record, validates it, and drops or rejects anything that doesn't match. Until S5, `from` is only claimed, not proven (see [S5](../docs/slices/s05-tokens-and-fingerprints.md)).

## Transport

| Item | Rule |
| --- | --- |
| Call | `POST /agent/messages`, `Content-Type: application/json`, the envelope as the body. |
| Reply | The reply envelope travels in the HTTP response of the same call (synchronous; S2 decision). `from` and `to` are swapped, `conversationId` is the same, `messageId` is new. |
| Errors | `400` with a problem body for an envelope that fails to parse, is addressed to another desk, or has a type this desk doesn't accept. A well-formed request the desk won't serve gets a `reject` envelope with `200`, not an error. |
| Retries | A repeated `messageId` gets the reply that was stored for it the first time, so a retry never produces a second quote. |
| Storage | Both desks store their own copy of every envelope they send and receive (`conversation_message`). |
| Addresses | Until S4, the café finds suppliers in its static `DESK_PEERS` list ([README](README.md#environment-variables)). |
| Auth | None until S5, which adds a registry token with scope `rfq:send` for `rfq`. The endpoint is only reachable inside the compose network. |

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
| `rfqId` | UUID | The `messageId` of the message this refuses. S3 reuses this body for refused counters and accepts. |
| `reason` | string | 1 to 200 characters, safe to show to the owner as plain text. |

## Later slices

S3 adds `counter`, revised `quote`, `accept`, `invoice` and `invoice.status`; S5 adds the envelope `signature`. Their bodies are defined in the [S3](../docs/slices/s03-counter-approve-invoice.md) and [S5](../docs/slices/s05-tokens-and-fingerprints.md) specs and move into this file when those slices land.
