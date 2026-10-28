![PayPal AI Hackathon](assets/banner.png)

# PayPal AI Hackathon 2026

Team entry by Matei and Robert for the [PayPal AI Hackathon](https://paypalaihackathon.devpost.com) on Devpost.

- Submissions close: **Nov 12, 2026, 12:00 PT**
- Required: PayPal developer platform (sandbox) + an AI tool, a public repo with an open-source license, setup and run instructions, and a demo video under 3 minutes on YouTube.

## Quick start

You need Docker with Compose v2 and about 6 GB of free memory.

```bash
git clone https://github.com/MateiS2002/paypal-ai-hackathon-2026.git
cd paypal-ai-hackathon-2026
cp .env.example .env        # placeholders are enough to start; add PayPal sandbox and OpenAI keys for the full demo
docker compose up --build --wait
```

Then open <http://localhost:8080>, choose **Café** or **Roaster**, and log in with username `cafe` or `roaster` and the `DESK_DEMO_PASSWORD` from `.env`.

Everything runs against the **PayPal sandbox** only. Developer setup (JDK, Node, running services from an IDE): [docs/local-setup.md](docs/local-setup.md).

## Repository layout

| Folder | What it is |
| --- | --- |
| [`trade-desk/`](trade-desk/) | Spring Boot app, one container per business (café, roaster, simulated suppliers) |
| [`registry/`](registry/) | Spring Boot app: network registry, agent card checks, supplier search |
| [`frontend/`](frontend/) | Vite + React + AG Grid Community, served by nginx |
| [`web/`](web/) | nginx: static landing pages and agent cards for the fictional companies |
| [`contracts/`](contracts/) | JSON Schemas, API routes, ports and env variables shared by all services |
| [`postgres/`](postgres/) | Init script: one database per service |

Planning notes live in [`docs/`](docs/).

Guidance for AI coding agents (Claude Code, Antigravity) lives in [`agents/`](agents/).

Licensed under the [MIT License](LICENSE).
