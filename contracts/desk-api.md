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

## Events (`GET /api/events`)

One SSE stream per desk. Each event's `data` is JSON shaped by [sse-event.schema.json](sse-event.schema.json): `{"type": "...", "data": {...}}`. Events have no SSE `event:` name, so `EventSource.onmessage` receives all of them. The server sends a comment line every 25 seconds as a heartbeat.

| Type | Slice | Data |
| --- | --- | --- |
| `desk.connected` | S0 | `{"deskId": "roaster"}`, sent once when the stream opens. |
