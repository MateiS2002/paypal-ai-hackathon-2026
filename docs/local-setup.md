# Local setup (laptop)

How to get the project running on your own machine, for both of us. Judges only need the root [README](../README.md) quick start.

## 1. Install

| Tool | Version | Why |
| --- | --- | --- |
| Git | any recent | |
| Docker Desktop (or Docker Engine + Compose v2) | Compose 2.20+ | Runs the whole stack. Give it at least **6 GB RAM** (Settings > Resources). |
| JDK | **21** (Temurin is fine) | Only to run the Java services or tests outside Docker. The Maven wrapper (`./mvnw`) downloads Maven itself. |
| Node.js | **22 LTS** (20.19+ works) | Only to run the frontend dev server with hot reload. |

On macOS: `brew install --cask docker temurin@21` and `brew install node@22`. On Windows use WSL 2 and clone inside the Linux file system, not `/mnt/c`, or builds get slow.

IntelliJ IDEA (Community is enough) opens `trade-desk/` and `registry/` as Maven projects; VS Code works for `frontend/`.

## 2. Clone and configure

```bash
git clone https://github.com/MateiS2002/paypal-ai-hackathon-2026.git
cd paypal-ai-hackathon-2026
cp .env.example .env
```

`.env` is gitignored, so this is where the real keys go. The placeholders are enough for S0; real keys are needed from S1 and S2:

- **PayPal sandbox** ([developer.paypal.com](https://developer.paypal.com) > Apps & Credentials, make sure the toggle says **Sandbox**):
  - Create a REST app for the roaster's sandbox business account: copy its client ID and secret into `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`.
  - Create a second REST app on another sandbox business account for the simulated suppliers: `PAYPAL_SIM_CLIENT_ID`, `PAYPAL_SIM_CLIENT_SECRET`.
  - Under Testing Tools > Sandbox Accounts, copy the sandbox **personal** account's email into `PAYPAL_SANDBOX_BUYER_EMAIL`.
  - Never paste live credentials anywhere. The code only talks to `api-m.sandbox.paypal.com`.
- **OpenAI** ([platform.openai.com](https://platform.openai.com) > API keys): `OPENAI_API_KEY`. Leave `OPENAI_MODEL` as is unless we switch models.
- `POSTGRES_PASSWORD`, `ELASTIC_PASSWORD`, `DESK_DEMO_PASSWORD`: any values; they only protect your local containers.

Keep keys out of commits, PR descriptions, issues and screenshots. If one leaks, rotate it in the PayPal or OpenAI dashboard first, then tell the other person.

## 3. Run everything

```bash
docker compose up --build --wait   # first build takes a few minutes
docker compose ps                  # every service should say (healthy)
```

Open <http://localhost:8080>, pick **Café** or **Roaster**, and log in with username `cafe` or `roaster` and the `DESK_DEMO_PASSWORD` from `.env`.

| Command | What it does |
| --- | --- |
| `docker compose logs -f roaster` | Follow one service's logs. |
| `docker compose up --build --wait roaster` | Rebuild and restart one service after a code change. |
| `docker compose down` | Stop everything, keep the data. |
| `docker compose down -v` | Stop and **delete** the databases and search index (needed after adding a database name to `docker-compose.yml`). |

Ports for debugging (all on `127.0.0.1`): frontend 8080, café 8081, roaster 8082, SIMULATED suppliers 8083 to 8085, the SIMULATED malicious supplier 8086 (only with `COMPOSE_PROFILES=malicious`), registry 8090, Postgres 5432 (user `postgres`), Elasticsearch 9200 (user `elastic`). Full list in [contracts/README.md](../contracts/README.md).

## 4. Day-to-day development

**Java (trade-desk, registry):**

```bash
cd trade-desk
./mvnw test            # unit and web tests, no Docker needed
```

To run a desk from the IDE instead of Docker, stop its container (`docker compose stop roaster`), keep Postgres running, and start `TradeDeskApplication` with these environment variables: `DESK_ID=roaster`, `DESK_NAME=Roaster & Co`, `DESK_DOMAIN=roaster.test`, `DESK_LOCATION=37.8044,-122.2712`, `DESK_REGION=US-CA`, `DESK_TIME_ZONE=America/Los_Angeles`, `DESK_DEMO_PASSWORD=...`, `POSTGRES_PASSWORD=...`, `SERVER_PORT=8082`. It connects to `localhost:5432/roaster` by default.

**Frontend:**

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173, hot reload
```

The dev server proxies `/cafe/api` to `localhost:8081` and `/roaster/api` to `localhost:8082`, so keep the desks running in Docker (or the IDE).

**Buyer agent check (S2, costs a few cents):** `RUN_LIVE_LLM=true OPENAI_API_KEY=... ./mvnw test -Dtest=NeedParserLiveTest` in `trade-desk/` runs the demo sentence 10 times against the real model and must parse it correctly at least 9 times.

**Before a PR:** `./mvnw test` in each Java service you touched, `npm run build && npm run lint` in `frontend/`, and `docker compose up --build --wait` from the repo root.

## Troubleshooting

- **A container is unhealthy:** `docker compose logs <service>`. A desk that exits at once usually has a missing or malformed `DESK_*` variable or a wrong `POSTGRES_PASSWORD`.
- **Changed `POSTGRES_PASSWORD` after the first run:** Postgres keeps the old one in its volume. Run `docker compose down -v`.
- **Elasticsearch exits with code 137:** Docker ran out of memory; raise Docker Desktop's memory limit.
- **Port already in use:** something else is on 8080 to 8090, 5432 or 9200. Stop it, or change the left side of that port mapping locally (don't commit it).
