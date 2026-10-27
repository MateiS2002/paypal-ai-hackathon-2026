# Working in parallel

Two people, Matei (Claude Code) and Robert (Antigravity), each driving coding agents on the same repo at the same time. The goal is to avoid stepping on each other and keep `main` runnable.

## Branches

- `main` is protected by convention: no direct pushes. Everything lands through a pull request.
- One branch per task, short-lived, branched from the latest `main`:
  - `matei/<short-topic>` or `robert/<short-topic>` for human-started work, e.g. `robert/paypal-invoices`.
  - Agent-created branches (such as `claude/...`) are fine; keep them to one task each.
- Rebase or merge `main` into your branch before opening a PR and again before merging if `main` moved.

## Splitting work

- Agree who owns which area before building (for example: one person on backend + PayPal + agent, the other on UI + AG Grid + Docker). Write the split in `context.md` once the idea is locked.
- Avoid editing files the other person owns in the same day. If you must, say so in the PR description.
- Shared contracts (API routes, data models, env variable names) change in their own small PR first, so both sides build against the same thing.
- Shared files that conflict often (`docker-compose.yml`, `.env.example`, lockfiles, the root README) should get small, focused edits. Regenerate lockfiles instead of hand-merging them.

## Pull requests

- Small and focused: one feature or fix per PR. Aim to merge the same day.
- The description says what changed, how to test it, and any new env variables or services.
- The other person reviews when possible; a quick look beats a long wait. Near the deadline, self-merging a small green PR is fine.
- Before merging, check that `docker compose up` still starts and the main demo flow still works.

## Commits

- Imperative, descriptive subject lines, e.g. `Add PayPal invoice creation endpoint`.
- Don't commit generated build output, `node_modules`, local databases or `.env` files.

## Talking to each other

- If your agent finds a bug in the other person's area, open an issue or leave a note in the PR instead of silently rewriting their code.
- Decisions that change the product, stack or scope go in `docs/` or `agents/context.md`, so the other person's agent picks them up too.
