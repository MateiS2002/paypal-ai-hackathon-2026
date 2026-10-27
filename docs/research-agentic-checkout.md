# Research: can an AI agent place real orders at real merchants?

Researched Oct 27, 2026. Short answer: partly, today, through different companies.

| Option | What it does | Use for us |
| --- | --- | --- |
| [PayPal Agent Toolkit](https://developer.paypal.com/agent-ready/overview) | Open-source tools for agents: orders, invoices, subscriptions, shipment tracking, transaction search, disputes. Works with MCP and common agent frameworks. | **Core building block**, runs in the sandbox |
| [PayPal Agent Ready](https://developer.paypal.com/agent-ready/overview) | Lets PayPal/Braintree merchants accept checkout from ChatGPT ([via ACP](https://developer.paypal.com/agent-ready/agentic-commerce-protocol)), Perplexity, Copilot, Google | Merchant side; shows PayPal's direction, not something we call |
| [Channel3](https://docs.trychannel3.com/index.md) | Product search across hundreds of millions of products, MCP support, 1,000 free searches a month. No checkout API found. | Product discovery (Idea 1) |
| [Rye](https://docs.rye.com) | Turns a product URL plus a buyer into a real order. Has staging; US shipping only. PayPal as payment not confirmed. | The "real orders later" story |
| ACP (OpenAI + Stripe), AP2 (Google, PayPal is a partner) | Open standards for agent checkout. Merchant adoption is uneven. See [comparison](https://crossmint.com/learn/agentic-payments-protocols-compared). | Pitch: where a real connector would plug in |
| Google A2A agent cards | A business publishes its agent's description at a well-known URL on its own domain (exact file name to confirm) | Discovery and trust in Idea 2 |

Browser bots that fill in checkout forms technically work but are fragile and usually against store terms, so we skip them.
