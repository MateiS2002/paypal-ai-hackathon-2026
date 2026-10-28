# S7: Messy order to invoice

**Owner:** Robert, with Matei pairing on the Spring AI setup · **Estimate:** 1.5 days · **Depends on:** S1

## Goal

A legacy customer who doesn't have a trade desk sends an order as a messy email or PDF. The roaster pastes or uploads it, the seller agent turns it into a draft invoice matched to the roaster's catalogue, the owner fixes anything flagged, and sends it as a real PayPal invoice through S1.

## Demo moment

Paste "hey its Luca from Bean There, can we get the usual 2 bags of the ethiopian + 1 decaf, same as last time, by thurs pls". A draft appears with two matched lines and the decaf line highlighted "which decaf?". Pick one, press Send, and the row lands in the ledger as SENT.

## In scope

- Roaster UI: "New order from text or PDF" panel next to the ledger: paste box, PDF upload, draft review form with low-confidence fields highlighted.
- Text extraction: pasted text as is; PDF text via Apache PDFBox (Apache-2.0) or Spring AI's PDF document reader. No OCR or photos (scope cut).
- **Seller agent (Spring AI):** text to `DraftOrder` with strict JSON-schema output: customer name or email, lines `{ rawText, quantity, unit, guessedSku, confidence }`, requested date, notes. Java validates the parsed `DraftOrder` before any matching (quantity a positive number, known unit, confidence 0 to 1, field lengths capped, date parseable); an invalid line is dropped into the review form as a flagged line, and an output that fails the schema entirely shows "couldn't read this order" with nothing pre-filled.
- **Java does the matching and the money:** look up `guessedSku` in the catalogue, fall back to fuzzy match on description only when exactly one SKU scores above a fixed threshold (otherwise the line is flagged and the owner picks), use catalogue prices (the LLM never sets a price), compute totals with `BigDecimal`. Customer: match an existing `customer` by email, then by name only when exactly one customer has that name (the demo's Bean There is seeded with its email); several matches are flagged for the owner to pick. If nothing matches, the review form asks for an email, and the invoice can't be sent without one.
- Confirm calls `InvoiceService` with `source = MESSY_ORDER` and passes the original text as `sourceText`, which S1 stores in `invoice.source_text`.
- 3 sample messy orders (2 text, 1 PDF) in `trade-desk/src/test/resources/messy-orders/` for tests and the demo.

## Out of scope

Reading a real mailbox (show "email inbox: coming soon"). Photos and handwriting.

## Contracts

| Route | What it does |
| --- | --- |
| `POST /api/orders/parse` | `multipart` (`text` or `file`), returns `DraftOrder` with matches and flags, no side effects |
| `POST /api/orders` | Confirmed draft, creates and sends the invoice, returns the ledger row |

## Data

`customer` (name, email, unique email) if S1 didn't add it. `invoice.source = MESSY_ORDER` and `invoice.source_text` (both already in S1's migration, so S7 adds no migration for them; the original text, for the partner history).

## Acceptance criteria

1. All 3 sample orders parse into the expected lines (quantities and SKUs) with GPT-6 Luna in at least 9 of 10 runs each.
2. An ambiguous line is flagged, never silently guessed; the invoice can't be sent until it's resolved.
3. Prices always come from the catalogue: an order text saying "at $2/kg" doesn't change the price (test).
4. A PDF over 5 MB or a non-PDF file is rejected with a clear message.
5. `InvoiceService` runs the roaster's approval limit (`DESK_INVOICE_APPROVAL_ABOVE`, a `BigDecimal`) in Java before sending: a confirmed order above it is saved as `AWAITING_APPROVAL` and is sent only after the owner presses "Approve and send" (test; no LLM involved). `POST /api/orders` returns the ledger row with that status.
6. The sent invoice behaves exactly like an S1 invoice (appears in PayPal sandbox, flips to PAID).

## Review-sensitive

- Whether this lives in the roaster desk or its own module. It's a package inside `trade-desk` so Robert can work on it without touching Matei's agent code.
