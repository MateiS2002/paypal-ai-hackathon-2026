# Idea scorecard

Live doc with charts: https://claude.ai/artifact/MnYJcP6TA2bsgVgeVSsAMY

Judges would likely score the two ideas almost the same (Idea 1: 38, Idea 2: 39 out of 50), so build effort decides. All numbers are estimates to argue over.

## Judging score (out of 10, equal weights)

| Criterion | Idea 1 | Idea 2 | Why |
| --- | --- | --- | --- |
| Tech implementation (first tie-breaker) | 7 | 9 | Idea 1's store orders are simulated; Idea 2 has agent-to-agent API, registry, invoicing |
| Design | 9 | 6 | OS-like fullscreen app vs business dashboards that need polish |
| Potential impact | 6 | 9 | Shopper convenience vs hours and cash flow for small suppliers |
| Innovation | 7 | 8 | AI shopping already exists; agent-to-agent B2B trade is rare |
| Presentation | 9 | 7 | Idea 1 is easy to film; Idea 2's split screen needs more explaining |
| **Total** | **38** | **39** | |

## Build effort (person-days)

Available: about 16 (2 people, about 8 full days each, Oct 30 to Nov 10).

| Idea 1 part | Days | Idea 2 part | Days |
| --- | --- | --- | --- |
| Home screen + Electron | 3 | Agent negotiation API | 3.5 |
| AI shopping agent | 3 | Trade desk UI | 3.5 |
| PayPal checkout + subs | 3 | PayPal invoices | 2.5 |
| Channel3 browse feed | 2 | Order intake (PDF) | 2 |
| Video and polish | 2 | Registry + agent cards | 2 |
| AG Grid views | 1.5 | Video and polish | 2 |
| Merchant connector | 1 | AG Grid ledger + offers | 1.5 |
| Docker + README | 1 | Simulated suppliers | 1.5 |
| | | Elastic (optional) | 1.5 |
| | | Docker + README | 1 |
| **Total** | **16.5** | **Total** | **21** |

## Feasibility ranking

1. **Idea 1:** safest to finish and polish in time. Risks: Channel3 data quality, Electron packaging, simulated store orders, crowded category.
2. **Idea 2:** higher ceiling and fewer competitors, but only with cuts. Risks: negotiation can be slow or unpredictable, many services in Docker, UI needs extra polish.

**Cuts that bring Idea 2 to about 17.5 days:** drop Elastic (1.5), one dashboard with a buyer/supplier switch (1), order intake from email text and PDFs only (1).

**Lean:** Idea 2 with those cuts if both of us can work close to full time; otherwise Idea 1.
