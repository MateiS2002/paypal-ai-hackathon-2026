# Project context

## What this is

Team entry by **Matei** and **Robert** for the [PayPal AI Hackathon 2026](https://paypalaihackathon.devpost.com) on Devpost: an app that integrates the **PayPal developer platform (sandbox)** with an AI tool. PayPal must be central to the product, not a bolt-on.

## Status

The product idea is **not locked yet** (decision due Oct 29, 2026). Two candidates are written up in [`docs/`](../docs/):

- [Idea 1: AI shopping OS](../docs/idea-1-ai-shopping-os.md) (B2C, Electron app, Channel3 + PayPal + AG Grid)
- [Idea 2: AI trade desk](../docs/idea-2-ai-trade-desk.md) (B2B, invoices + agent-to-agent supplier network)

Until it's locked, don't scaffold product code for one idea unless asked. Once it's locked, this section gets replaced with the chosen idea, stack, folder layout and commands.

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
| Rules, prizes, timeline | [docs/strategy.md](../docs/strategy.md) |
| Idea comparison and effort estimates | [docs/idea-scorecard.md](../docs/idea-scorecard.md) |
| Which agent-checkout APIs exist (PayPal Agent Toolkit, Channel3, Rye, ACP, AP2) | [docs/research-agentic-checkout.md](../docs/research-agentic-checkout.md) |
| PayPal APIs | [developer.paypal.com](https://developer.paypal.com), [PayPal Agent Toolkit](https://github.com/paypal/agent-toolkit) |

## Priorities when making trade-offs

1. A working, demoable end-to-end flow beats breadth. Scope is tight (about 16 person-days).
2. Real PayPal sandbox API calls beat mocks wherever feasible; that's what Technological Implementation rewards. Simulate only what the sandbox can't do, and label simulations clearly in code.
3. One-command startup for judges (`docker compose up`) must keep working on every merge to `main`.
