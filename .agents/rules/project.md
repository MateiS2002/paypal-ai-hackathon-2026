---
trigger: always_on
---

# Project rules (Antigravity)

This workspace's shared guidance for all coding agents lives in `agents/` at the repo root. Read `agents/context.md`, `agents/rules.md` and `agents/workflow.md` before planning or editing code, and follow them. Edit guidance there, not in this file.

Always:

- Use the PayPal **sandbox** only. Never live credentials or endpoints.
- Never commit secrets. Use a gitignored `.env`; document variables in `.env.example`. The repo is public.
- Keep `docker compose up` working and the README run steps accurate.
- Work on a branch (`robert/<topic>`) and open a small PR; never push to `main`.
- Stay inside the task; ask before big refactors or stack changes.
