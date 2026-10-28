# S0: Skeleton and contracts

**Owner:** Matei · **Estimate:** 1 day · **Depends on:** nothing

## Goal

Every service from [architecture.md](../architecture.md#services) exists, starts with `docker compose up` and reports healthy, even though it does almost nothing. The shared contracts are written down so S1, S2 and S4 can be built in parallel without guessing.

## Demo moment

None for judges. For us: a fresh clone plus `.env` gives green health checks for every container in under 3 minutes.

## In scope

- Folder layout:

  ```text
  trade-desk/     Spring Boot app, one container per business
  registry/       Spring Boot app
  frontend/       Vite + React + AG Grid Community, built into nginx
  web/            nginx: static landing pages and agent cards
  contracts/      JSON Schemas and example messages shared by all services
  docker-compose.yml
  .env.example
  ```

- `trade-desk` and `registry`: Java 21, Spring Boot 4.1, Actuator `/actuator/health` (open, used only by compose health checks and not proxied by the frontend), Flyway with an empty first migration, Spring Security on with one demo login per desk.
- `docker-compose.yml` with `postgres` (one database per service, created by an init script), `cafe`, `roaster`, `registry`, `web`, `frontend`. Health checks and `depends_on: condition: service_healthy`. Elasticsearch and the simulated suppliers are added by the slices that need them (S6, S2).
- `frontend` nginx proxies `/cafe/api/**` to the café desk and `/roaster/api/**` to the roaster desk, so the browser talks to one origin and CORS stays closed.
- A single CI-free check: `docker compose up --wait` succeeds locally. CodeQL is the only required GitHub check.
- `agents/context.md` gets the folder layout and run commands (its own TODO says so).

## Out of scope

Any business logic, LLM calls or PayPal calls.

## Contracts

These are the shared contracts the workflow says go first. Put them in `contracts/` and this list in `agents/context.md`.

**Ports (host side, for local debugging):** frontend `8080`, café `8081`, roaster `8082`, simulated suppliers `8083` to `8085`, registry `8090`, Postgres `5432`, Elasticsearch `9200`.

**Environment variables** (all in `.env.example` with placeholders):

| Variable | Used by | Notes |
| --- | --- | --- |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | roaster | Sandbox REST app. The base URL is a constant `https://api-m.sandbox.paypal.com`, not an env variable, so nothing can point it at live. |
| `PAYPAL_SIM_CLIENT_ID`, `PAYPAL_SIM_CLIENT_SECRET` | simulated suppliers | One shared sandbox merchant for all SIMULATED suppliers (see architecture gotchas). |
| `PAYPAL_SANDBOX_BUYER_EMAIL` | café | The sandbox buyer account that receives invoices. |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | desks, registry | Model defaults to GPT-6 Luna. |
| `POSTGRES_PASSWORD` | postgres, desks, registry | |
| `ELASTIC_PASSWORD` | elasticsearch, registry | Added in S6. |
| `DESK_DEMO_PASSWORD` | desks | Demo login password, same for every desk. |

Per-desk settings that aren't secret (`DESK_ID`, `DESK_NAME`, `DESK_DOMAIN`, `DESK_LOCATION`, `DESK_PERSONA`, `DESK_SIMULATED`) live in `docker-compose.yml`, not `.env`.

**Frontend to desk (REST + SSE):** every desk exposes `/api/**` for its own UI and one SSE stream `GET /api/events`. Events are `{"type": "invoice.updated" | "quote.received" | "approval.needed" | ..., "data": {...}}`. Each slice adds its own routes and event types to `contracts/desk-api.md`.

**Desk to desk:** every agent message is a JSON envelope; slices define the `type` and `body`.

```json
{
  "messageId": "uuid",
  "type": "rfq | quote | counter | accept | reject | invoice | invoice.status",
  "from": "cafe.test",
  "to": "roaster.test",
  "conversationId": "uuid",
  "sentAt": "2026-11-03T10:00:00Z",
  "body": {}
}
```

Money is always `{"amount": "12.50", "currency": "USD"}`: a decimal string on the wire, `BigDecimal` in Java, never a float.

## Data

Empty schemas plus Flyway baseline. Each slice adds its own migrations.

## Acceptance criteria

1. On a fresh clone with `.env` copied from `.env.example`, `docker compose up --wait` exits 0 and `docker compose ps` shows every service healthy.
2. `http://localhost:8080` shows the frontend shell with "Café" and "Roaster" login choices.
3. `http://localhost:8080/roaster/api/me` (nginx maps `/roaster/api/**` to the roaster desk's `/api/**`) returns the logged-in desk user after login, and 401 before.
4. `contracts/` holds the envelope schema and `desk-api.md`; `agents/context.md` lists folders and run commands.
5. No secrets in git; `.env` is gitignored.

## Review-sensitive

- Login approach (form login with session cookie vs JWT). Pick session cookies for now: simplest with CSRF on. Unauthenticated `/api/**` requests get 401 (`HttpStatusEntryPoint`), not a redirect to `/login`, so the React app can show its own login screen.
- Whether simulated suppliers are separate containers or tenants in one container (memory).
