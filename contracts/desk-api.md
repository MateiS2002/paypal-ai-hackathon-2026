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

## Events (`GET /api/events`)

One SSE stream per desk. Each event's `data` is JSON shaped by [sse-event.schema.json](sse-event.schema.json): `{"type": "...", "data": {...}}`. Events have no SSE `event:` name, so `EventSource.onmessage` receives all of them. The server sends a comment line every 25 seconds as a heartbeat.

| Type | Slice | Data |
| --- | --- | --- |
| `desk.connected` | S0 | `{"deskId": "roaster"}`, sent once when the stream opens. |
| `invoice.updated` | S1 | An [invoice row](#invoice-row), sent when an invoice is created or its PayPal status changes. The UI replaces the row with the same `id`. Example: [sse-invoice-updated.json](examples/sse-invoice-updated.json). |

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
