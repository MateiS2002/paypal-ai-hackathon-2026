# Contracts

Shared contracts that every service builds against. Change them in their own small PR before the code that uses them, so both of us build against the same thing ([workflow](../agents/workflow.md#splitting-work)).

| File | What it defines |
| --- | --- |
| [envelope.schema.json](envelope.schema.json) | The JSON envelope of every desk-to-desk message (`rfq`, `quote`, `counter`, `accept`, `reject`, `invoice`, `invoice.status`). Slices define each type's `body`. |
| [agent-messages.md](agent-messages.md) | `POST /agent/messages` and each message type's `body`: `rfq`, `quote` and `reject` (S2), `counter`, revised `quote`, `accept`, `invoice` and `invoice.status` (S3). |
| [money.schema.json](money.schema.json) | Money: `{"amount": "12.50", "currency": "USD"}`, a decimal string on the wire, `BigDecimal` in Java, never a float. |
| [sse-event.schema.json](sse-event.schema.json) | One event on a desk's `GET /api/events` stream. |
| [desk-api.md](desk-api.md) | Frontend-to-desk REST routes and SSE event types. |
| [examples/](examples/) | Example messages. The trade desk's tests parse these, so keep them valid. |

## Ports (host side, for local debugging)

| Service | Port |
| --- | --- |
| `frontend` (the app judges open) | 8080 |
| `cafe` desk | 8081 |
| `roaster` desk | 8082 |
| simulated suppliers (S2) | 8083 to 8085 |
| `supplier-e`, the SIMULATED malicious supplier (S8; only with `COMPOSE_PROFILES=malicious`) | 8086 |
| `registry` | 8090 |
| `postgres` | 5432 |
| `elasticsearch` | 9200 |

All are bound to `127.0.0.1`. Inside the compose network every Spring app listens on `8080`.

## Environment variables

Secrets live in `.env` (gitignored), with placeholders in [`.env.example`](../.env.example):

| Variable | Used by | Notes |
| --- | --- | --- |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | roaster | Sandbox REST app. The base URL is a constant `https://api-m.sandbox.paypal.com` in code, not a variable, so nothing can point it at live. |
| `PAYPAL_SIM_CLIENT_ID`, `PAYPAL_SIM_CLIENT_SECRET` | simulated suppliers | One shared sandbox merchant for every SIMULATED supplier. From the S3 code PR on, `docker-compose.yml` passes them to `supplier-b`, `supplier-c` and `supplier-d` as their `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`, so their invoices come from this merchant. |
| `PAYPAL_SANDBOX_BUYER_EMAIL` | café, roaster | The sandbox buyer account that receives invoices. The roaster seeds it as the customer Bean There; the café sends it as `billTo` in every `accept` (S3). |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | desks, registry | Model defaults to GPT-6 Luna. |
| `POSTGRES_PASSWORD` | postgres, desks, registry | Required. |
| `ELASTIC_PASSWORD` | elasticsearch, registry | Required (at least 6 characters). Used from S6. |
| `DESK_DEMO_PASSWORD` | desks | Required. Demo login password, the same for every desk. |
| `COMPOSE_PROFILES` | compose | Set to `malicious` in `.env.example` (S8): starts `supplier-e`, the SIMULATED malicious supplier, and adds it to the café's `DESK_PEERS`. Remove the line (or leave it empty) to switch the supplier off; nothing else in the demo depends on it. Compose itself reads the variable, and `docker-compose.yml` also uses it to decide whether the café asks `supplier-e`, so a `--profile malicious` on the command line alone starts the container but the café does not ask it. An existing database volume needs `docker compose down -v` once, because `supplier-e`'s database is created only on a fresh volume. |
| `DESK_MALICIOUS_EXTRA_FIELD` | `supplier-e` | `true` makes the malicious supplier's quote carry `"autoApprove": true`, which the café refuses whole (S8). Default `false`: the quote is well-formed and the policy engine blocks its $40/kg price. |

Per-desk settings that aren't secret are set per service in [`docker-compose.yml`](../docker-compose.yml), not `.env`:

| Variable | Example | Notes |
| --- | --- | --- |
| `DESK_ID` | `roaster` | Lowercase. Also the login username, the database name and the session cookie prefix. |
| `DESK_NAME` | `Roaster & Co` | Shown in the UI. |
| `DESK_DOMAIN` | `roaster.test` | The business's domain on the compose network. |
| `DESK_LOCATION` | `37.7749,-122.4194` | `lat,lon`. |
| `DESK_REGION` | `US-CA` | ISO 3166-2. |
| `DESK_TIME_ZONE` | `America/Los_Angeles` | IANA zone. The desk refuses to start if it isn't valid. |
| `DESK_INVOICE_APPROVAL_ABOVE` | `500.00` | Roaster only, used by S7. Amount in USD; messy orders in another currency are always held. |
| `DESK_PERSONA` | `fast-pricey` | Which seeded catalogue a supplier desk sells from (S2): `roaster` (the real roaster), or the SIMULATED personas `fast-pricey`, `cheap-slow`, `low-stock` and `malicious` (S8: a prompt-injected $40/kg quote that never moves on a counter). Empty means the desk sells nothing and rejects every RFQ (the café). |
| `DESK_PEERS` | `roaster.test=http://roaster:8080` | Buyer desks only (S2): comma-separated `domain=base URL` pairs of the suppliers that get each RFQ. The domain goes in the envelope's `to`; the URL is where `POST /agent/messages` is sent inside compose. S4 replaces this list with a registry lookup. |
| `DESK_SIMULATED` | `false` | `true` for SIMULATED suppliers. |
| `DESK_BUYERS` | `cafe.test=http://cafe:8080` | Supplier desks only (S3): comma-separated `domain=base URL` pairs of the buyer desks this supplier may send `invoice.status` to, in the same format as `DESK_PEERS`. A buyer not in the list gets no status pushes; the supplier never sends to an address taken from a message. Set on the roaster and the SIMULATED suppliers in `docker-compose.yml` from the S3 code PR on. S4 replaces this list with a registry lookup. |
