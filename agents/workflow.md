# Working in parallel

Two people, Matei (Claude Code) and Robert (Antigravity), each driving coding agents on the same repo at the same time. The goal is to avoid stepping on each other and keep `main` runnable.

## Branches

- `main` is protected by a ruleset: pull request required (0 approvals, so a green PR can be self-merged), required check `Analyze (actions)` (CodeQL), no force pushes, no deletion. Merges are **squash only**, so the PR title becomes the commit on `main`; make it a good commit subject. Setup details: [docs/repo-setup.md](../docs/repo-setup.md).
- One branch per task, short-lived, branched from the latest `main`:
  - `matei/<short-topic>` or `robert/<short-topic>` for human-started work, e.g. `robert/paypal-invoices`.
  - Agent-created branches (such as `claude/...`) are fine; keep them to one task each.
- Rebase or merge `main` into your branch before opening a PR and again before merging if `main` moved.

## Splitting work

- Ownership is decided per task (see [context.md](context.md)). Before starting, say who takes it so two agents don't build the same thing.
- Avoid editing files the other person owns in the same day. If you must, say so in the PR description.
- Shared contracts (API routes, data models, env variable names) change in their own small PR first, so both sides build against the same thing.
- Shared files that conflict often (`docker-compose.yml`, `.env.example`, lockfiles, the root README) should get small, focused edits. Regenerate lockfiles instead of hand-merging them.

## Pull requests

- Small and focused: one feature or fix per PR. Aim to merge the same day.
- The description says what changed, how to test it, and any new env variables or services.
- The other person reviews when possible; a quick look beats a long wait. Near the deadline, self-merging a small green PR is fine.
- Before merging, check that `docker compose up` still starts and the main demo flow still works.
- CodeRabbit reviews every PR into `main` (drafts and titles containing `WIP` are skipped) and CodeQL runs as a required check. Both review against `agents/` and `docs/architecture.md`, so keep those accurate.
- Address or reply to every CodeRabbit comment before merging; if one is wrong for this project, reply saying why so it learns. `@coderabbitai review` re-reviews new commits. More commands in [docs/repo-setup.md](../docs/repo-setup.md#day-to-day).
- CODEOWNERS requests a review from both of us on every PR. Code-owner approval is not required.
- Use the PR template's checklist.

## Commits

- Imperative, descriptive subject lines, e.g. `Add PayPal invoice creation endpoint`.
- Don't commit generated build output, `node_modules`, local databases or `.env` files.

## Talking to each other

- If your agent finds a bug in the other person's area, open an issue or leave a note in the PR instead of silently rewriting their code.
- Decisions that change the product, stack or scope go in `docs/` or `agents/context.md`, so the other person's agent picks them up too.
