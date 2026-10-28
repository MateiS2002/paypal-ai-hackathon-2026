# Project context

## What this is

Team entry by **Matei** and **Robert** for the [PayPal AI Hackathon 2026](https://paypalaihackathon.devpost.com) on Devpost: an app that integrates the **PayPal developer platform (sandbox)** with an AI tool. PayPal must be central to the product, not a bolt-on.

## Status

**Locked on Oct 28, 2026: Idea 2, the AI trade desk** ([product doc](../docs/idea-2-ai-trade-desk.md), [architecture and stack](../docs/architecture.md)). Idea 1 is archived in `docs/` for reference.

Stack: Java 21, Spring Boot 4.1, Spring AI 2.0 (OpenAI API, model set by `OPENAI_MODEL`), Spring Security, Postgres, Elasticsearch, React (Vite) + AG Grid Community, nginx for static landing pages and agent cards, all under `docker compose`.

The security model (money rules in Java, agent keys and tokens, untrusted LLM output) is a hard rule: see [rules.md](rules.md#security-model).

Ownership is dynamic and decided per task: Matei leads backend and agents, Robert leans QA and picks up development tasks as he goes.

## Folder layout and commands

Skeleton from slice S0 ([spec](../docs/slices/s00-skeleton-and-contracts.md)):

```text
trade-desk/     Spring Boot app, one container per business (cafe, roaster, simulated suppliers via DESK_* env)
registry/       Spring Boot app: enrollment, agent card checks, supplier search, tokens, fingerprints
frontend/       Vite + React + AG Grid Community, built into nginx; proxies /cafe/api/** and /roaster/api/**
web/            nginx: static landing pages and agent cards
contracts/      JSON Schemas, desk-api.md, ports and env variables (the shared contracts)
postgres/       init script: one database per service
docker-compose.yml, .env.example
```

| Task | Command |
| --- | --- |
| Run everything | `cp .env.example .env && docker compose up --build --wait`, then <http://localhost:8080> |
| Java tests | `./mvnw test` in `trade-desk/` or `registry/` |
| Frontend dev server | `npm install && npm run dev` in `frontend/` (proxies to the desks on 8081 and 8082) |
| Frontend checks | `npm run build && npm run lint` in `frontend/` |

Shared contracts (keep in sync, change in their own small PR first): [contracts/README.md](../contracts/README.md) for ports and env variables, [envelope.schema.json](../contracts/envelope.schema.json) for desk-to-desk messages, [money.schema.json](../contracts/money.schema.json), [desk-api.md](../contracts/desk-api.md) for frontend-to-desk routes and SSE events. Laptop setup: [docs/local-setup.md](../docs/local-setup.md).

## Submission requirements (missing any of these fails judging stage 1)

- Deadline: **Nov 12, 2026, 12:00 PT**. We aim to submit Nov 11.
- **Public GitHub repo** with an **open-source license** file, visible in the repo's About section.
- **Setup and run instructions** in the root README. Plan: everything starts with `docker compose up`, plus a hosted demo if possible.
- A **demo video under 3 minutes** on YouTube. Judges may judge from the description, images and video alone, so the demo flow must work end to end and look good on screen.
- Uses the PayPal developer platform (sandbox) and an AI tool.

Judging criteria (equal weight): Technological Implementation (first tie-breaker), Design, Potential Impact, Innovation, Presentation. Prize targets: Best Use of Agentic Commerce, AG Grid sponsor prize. Details in [docs/strategy.md](../docs/strategy.md).

## Timeline

| Dates | Goal |
| --- | --- |
| Oct 27 to 29 | Pick and lock the idea |
| Oct 30 to Nov 8 | Build |
| Nov 9 to 10 | Polish and record the video |
| Nov 11 | Submit, one day early |

## Where to look

| Need | Look in |
| --- | --- |
| Architecture, stack and decisions | [docs/architecture.md](../docs/architecture.md) |
| Rules, prizes, timeline | [docs/strategy.md](../docs/strategy.md) |
| Idea comparison and effort estimates | [docs/idea-scorecard.md](../docs/idea-scorecard.md) |
| CodeRabbit, CodeQL, branch protection, one-time GitHub steps | [docs/repo-setup.md](../docs/repo-setup.md) |
| Which agent-checkout APIs exist (PayPal Agent Toolkit, Channel3, Rye, ACP, AP2) | [docs/research-agentic-checkout.md](../docs/research-agentic-checkout.md) |
| PayPal APIs | [developer.paypal.com](https://developer.paypal.com), [PayPal Agent Toolkit](https://github.com/paypal/agent-toolkit) |

## Priorities when making trade-offs

1. A working, demoable end-to-end flow beats breadth. Scope is tight (about 16 person-days).
2. Real PayPal sandbox API calls beat mocks wherever feasible; that's what Technological Implementation rewards. Simulate only what the sandbox can't do, and label simulations clearly in code.
3. One-command startup for judges (`docker compose up`) must keep working on every merge to `main`.
