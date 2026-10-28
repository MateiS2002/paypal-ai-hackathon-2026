# S2: Post a need, get quotes

**Owner:** Matei · **Estimate:** 2 days · **Depends on:** S0

## Goal

The café owner types a need in plain words. The buyer agent turns it into a structured, validated purchase request, sends an RFQ directly to each supplier desk, and the quotes stream into an AG Grid offers table ranked by the café's priority.

## Demo moment

Owner types "50 kg Brazilian beans by Friday, prioritise speed". A parsed summary card appears (product, origin, 50 kg, deadline, priority: speed), then three quotes pop into the grid one by one, best first.

## In scope

- Café UI: "Post a need" box, the parsed request card (owner can fix fields before sending), AG Grid offers table (supplier, price/kg, total, delivery date, stock, note, SIMULATED badge).
- **Buyer agent (Spring AI):** text to `PurchaseRequest` with strict JSON-schema structured output. Java validates it (quantity > 0, known unit, deadline in the future) and rejects or asks again; the LLM never sends anything itself.
- **RFQ fan-out:** Java sends the RFQ envelope to every supplier in a static peer list (`DESK_PEERS` in `docker-compose.yml`). S4 replaces the list with a registry lookup, S6 with search.
- **Seller side (same app, other desks):** price, stock and delivery date come from Java (seeded catalogue + persona), within the owner's price floor. The LLM only writes the one-line note. Quote is returned as the response to the RFQ.
- **Simulated suppliers:** 3 extra `trade-desk` containers with `DESK_SIMULATED=true` and a persona: `fast-pricey`, `cheap-slow`, `low-stock`. Each adds a small random delay so quotes visibly arrive one at a time. Labelled SIMULATED in UI and code.
- **Ranking in Java:** by price, by delivery date, or a weighted mix, depending on the priority.

## Out of scope

Counter-offers, approval, invoices (S3). Registry and tokens (S4, S5): in this slice desk-to-desk calls are only reachable inside the compose network. Mark that with a `// TODO S5: require scoped token` at the endpoint.

## Contracts

Desk API (café):

| Route | What it does |
| --- | --- |
| `POST /api/needs/parse` | `{ "text" }` to `PurchaseRequest` (no side effects) |
| `POST /api/needs` | Confirmed `PurchaseRequest`, starts the fan-out, returns `needId` |
| `GET /api/needs/{id}/quotes` | Current quotes, ranked |
| SSE | `quote.received` with the ranked row |

`PurchaseRequest`:

```json
{ "product": "green coffee beans", "origin": "Brazil", "quantity": "50", "unit": "kg",
  "deliverBy": "2026-11-07", "priority": "SPEED | PRICE | BALANCED", "maxUnitPrice": Money | null }
```

Agent API (every desk): `POST /agent/messages` with the S0 envelope.

- `type: "rfq"`, body: the `PurchaseRequest` minus anything private (no stock levels, no budget).
- Response `type: "quote"`, body: `{ "rfqId", "unitPrice": Money, "quantity", "availableQuantity", "unit", "deliveryDate", "validUntil", "note" }`, or `type: "reject"` with a reason. `quantity` is what the supplier offers for this RFQ; `availableQuantity` is its current stock in the same unit, shown in the offers grid's stock column.

## Data

Café: `need` (request JSON, status), `quote` (need_id, supplier, unit_price_minor, quantity, delivery_date, valid_until, note, raw_message JSON).

Every desk: `catalogue_item` (sku, description, origin, unit, list_price_minor, floor_price_minor, stock, lead_days), seeded per persona by Flyway or a seed file; `conversation_message` (its own copy of every sent and received envelope).

## Acceptance criteria

1. Typing the demo sentence produces the correct structured request in 9 out of 10 tries with GPT-6 Luna; a deliberately vague sentence ("some beans") asks for the missing fields instead of guessing.
2. Three quotes appear in the grid within 10 seconds, arriving one at a time, ranked correctly for each priority setting.
3. No quote is ever below a supplier's floor price or above its stock, whatever the LLM writes in the note (unit test with a stubbed LLM).
4. A malformed quote (missing fields, negative price, wrong currency) is dropped and logged, not shown.
5. The RFQ body sent to suppliers contains no café stock level or budget.
6. Every sent and received envelope is stored on both desks.

## Review-sensitive

- Sync quote-in-response vs async callback (`POST /agent/messages` back to the buyer). Sync is simpler; async is closer to real B2B and to A2A tasks.
- How much the seller LLM decides. Current answer: nothing about numbers.
