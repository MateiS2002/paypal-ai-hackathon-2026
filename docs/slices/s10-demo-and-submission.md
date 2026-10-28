# S10: Demo, README, submission

**Owner:** Robert leads, both record · **Estimate:** 2 days · **Depends on:** all slices that made the cut

## Goal

Pass judging stage 1 with no doubt and make the video carry the project, since judges may judge from the description, images and video alone.

## Demo moment

The video itself: under 3 minutes, problem, who it's for, one working end-to-end flow.

## In scope

- **Root README:** what it is, a screenshot or GIF, prerequisites (Docker, about 4 GB free RAM, a PayPal sandbox app, an OpenAI key), `cp .env.example .env`, `docker compose up`, which URL to open, demo logins, sandbox buyer login steps, troubleshooting (ports, memory, Elasticsearch off).
- **Seed data:** one command or automatic Flyway seed so a fresh run looks like the video (catalogues, a few past invoices in the ledger, café stock at 22 kg).
- **Demo script** in `docs/demo-script.md` with timings, matching the flow in [idea-2-ai-trade-desk.md](../idea-2-ai-trade-desk.md#demo-flow-split-screen-café-vs-roaster): reorder trigger, quotes, counter, approval, PayPal payment, ledger PAID, malicious supplier, messy order, then a "coming soon" label for the late-payer reminder (unless S9 reminders shipped, see the cut order in [README](README.md)).
- **Recording:** 1080p, split screen, captions; uploaded to YouTube (public or unlisted).
- **Devpost entry:** description, images, video link, repo link, "built with" tags (PayPal Invoicing, PayPal Agent Toolkit MCP, Spring AI, OpenAI, AG Grid, Elasticsearch).
- **Repo:** MIT license visible in the GitHub About section, topics set, no secrets in history (run gitleaks once over the whole history).
- **Hosted demo (stretch):** one VM or Render instance with webhooks on, if time allows.

## Acceptance criteria

1. Robert runs the README steps on a machine that has never seen the repo, and the full demo works without help.
2. Video is under 3:00 and shows real PayPal sandbox pages.
3. Devpost checklist from [strategy.md](../strategy.md#key-rules) is complete by Nov 11.
4. Everything simulated is labelled SIMULATED in the UI and said out loud in the video.

## Review-sensitive

- Video storyline: lead with the café's problem or with the network. Decide after a first rough cut on Nov 9.
