# Contracts

Shared contracts that every service builds against. Change them in their own small PR before the code that uses them, so both of us build against the same thing ([workflow](../agents/workflow.md#splitting-work)).

| File | What it defines |
| --- | --- |
| [envelope.schema.json](envelope.schema.json) | The JSON envelope of every desk-to-desk message (`rfq`, `quote`, `counter`, `accept`, `reject`, `invoice`, `invoice.status`). Slices define each type's `body`. |
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
| `registry` | 8090 |
| `postgres` | 5432 |
| `elasticsearch` | 9200 |

All are bound to `127.0.0.1`. Inside the compose network every Spring app listens on `8080`.

## Environment variables

Secrets live in `.env` (gitignored), with placeholders in [`.env.example`](../.env.example):

| Variable | Used by | Notes |
| --- | --- | --- |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | roaster | Sandbox REST app. The base URL is a constant `https://api-m.sandbox.paypal.com` in code, not a variable, so nothing can point it at live. |
| `PAYPAL_SIM_CLIENT_ID`, `PAYPAL_SIM_CLIENT_SECRET` | simulated suppliers | One shared sandbox merchant for every SIMULATED supplier. |
| `PAYPAL_SANDBOX_BUYER_EMAIL` | café, roaster | The sandbox buyer account that receives invoices. The roaster seeds it as the customer Bean There. |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | desks, registry | Model defaults to GPT-6 Luna. |
| `POSTGRES_PASSWORD` | postgres, desks, registry | Required. |
| `ELASTIC_PASSWORD` | elasticsearch, registry | Required (at least 6 characters). Used from S6. |
| `DESK_DEMO_PASSWORD` | desks | Required. Demo login password, the same for every desk. |

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
| `DESK_PERSONA` | | Simulated suppliers' personality (S2). |
| `DESK_SIMULATED` | `false` | `true` for SIMULATED suppliers. |
