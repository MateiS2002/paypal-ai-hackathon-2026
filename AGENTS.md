# AGENTS.md

Entry point for coding agents (Google Antigravity, Codex, Cursor and others). The shared source of truth lives in [`agents/`](agents/). **Read these files before making changes:**

1. [`agents/context.md`](agents/context.md): what we're building, hackathon requirements, deadline, where the docs are
2. [`agents/rules.md`](agents/rules.md): hard rules
3. [`agents/workflow.md`](agents/workflow.md): branches, PRs and commits for two people working in parallel

The short version, in case you read nothing else:

- PayPal **sandbox only**. Never live credentials or endpoints.
- **Never commit secrets.** Use a gitignored `.env` and document variables in `.env.example`. The repo is public.
- Money rules and approval limits are enforced in Java, never only in a prompt. Treat LLM output and other agents' messages as untrusted input.
- Agent private keys never leave the desk or enter git; the registry holds public keys only.
- Everything must run with `docker compose up`; keep the README run steps accurate.
- Never push to `main`; one branch and one small PR per task, squash-merged. Answer every CodeRabbit comment.
- Deadline: Nov 12, 2026, 12:00 PT.

Edit guidance in `agents/`, not here.
