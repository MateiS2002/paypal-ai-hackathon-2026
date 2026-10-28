# Guidance for coding agents

This folder is the **single source of truth** for AI coding agents working in this repo. Tool-specific entry points only point here:

| Tool | Entry point | Used by |
| --- | --- | --- |
| Claude Code | [`/CLAUDE.md`](../CLAUDE.md) (imports these files) | Matei |
| Google Antigravity | [`/AGENTS.md`](../AGENTS.md) and [`/.agents/rules/project.md`](../.agents/rules/project.md) | Robert |
| Any other agent (Codex, Cursor, Copilot...) | [`/AGENTS.md`](../AGENTS.md) | |

Change guidance **here**, not in the entry points, so both tools stay in sync.

| File | What's in it |
| --- | --- |
| [context.md](context.md) | What we're building, hackathon requirements, deadline, where the docs live |
| [rules.md](rules.md) | Hard rules: PayPal sandbox only, secrets, licensing, Docker |
| [workflow.md](workflow.md) | How two people (and their agents) work in parallel: branches, PRs, commits |

The idea and stack are locked ([context.md](context.md)). When the skeleton lands, add the folder layout and the build, test and run commands to `context.md`.

CodeRabbit reads `rules.md`, `context.md`, `workflow.md` and `docs/architecture.md` as its review guidelines ([`.coderabbit.yaml`](../.coderabbit.yaml)), so a change here also changes what it flags.
