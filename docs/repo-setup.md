# Repo setup: reviews, security scans and collaboration

What's committed, what each of us has to click once in GitHub, and how to use it day to day. Added Oct 28, 2026.

## What's in the repo

| File | What it does |
| --- | --- |
| [`.coderabbit.yaml`](../.coderabbit.yaml) | CodeRabbit review settings: quiet "chill" profile, project-specific instructions per path (money rules in Java, sandbox only, SIMULATED labels, compose/README sync), two pre-merge checks, gitleaks and other linters. |
| [`.github/workflows/codeql.yml`](../.github/workflows/codeql.yml) | CodeQL on every PR to `main`, every push to `main`, and weekly. Scans GitHub Actions now; Java and JS/TS switch on by themselves once those files exist. Java uses `build-mode: none`, so no Maven setup is needed. |
| [`.github/dependabot.yml`](../.github/dependabot.yml) | Weekly update PRs for GitHub Actions. Maven, npm and Docker entries are commented out until the skeleton lands. |
| [`.github/pull_request_template.md`](../.github/pull_request_template.md) | Short checklist matching `agents/rules.md`. |
| [`.github/CODEOWNERS`](../.github/CODEOWNERS) | Auto-requests a review. Add Robert's login once he's a collaborator. |

### Does CodeRabbit know about our project?

Yes. CodeRabbit reads "code guidelines" files from the repo and uses them in every review. It picks up common agent files like `CLAUDE.md` and `.cursorrules` on its own, but our `CLAUDE.md` only `@`-imports `agents/*.md`, and CodeRabbit doesn't follow those imports. So `.coderabbit.yaml` points it straight at `agents/rules.md`, `agents/context.md`, `agents/workflow.md` and `docs/architecture.md`. On top of that:

- `path_instructions` give it rules per area (Java backend, frontend, landing pages, Docker, workflows).
- **Learnings**: reply to a CodeRabbit comment in plain words ("we always use BigDecimal for money here") and it remembers that for this repo.
- It reads linked issues and earlier PRs, and searches the web for library docs (Spring AI 2.0 and so on).

Docs: [configuration reference](https://docs.coderabbit.ai/reference/configuration), [knowledge base](https://docs.coderabbit.ai/integrations/knowledge-base).

## One-time steps (Matei, as repo admin)

About 15 minutes in total.

1. **Install CodeRabbit.** Go to [app.coderabbit.ai](https://app.coderabbit.ai), sign in with GitHub, and install the GitHub app on **only** `paypal-ai-hackathon-2026`. Public repos get the paid-tier features for free, so pick the free/open-source option and don't enter a card. It reads `.coderabbit.yaml` from the PR branch, so this PR is the first one it reviews.
2. **Add Robert.** Settings → Collaborators → Add people, with **Write** access. Then put his login in `.github/CODEOWNERS`.
3. **Code security** (Settings → Advanced Security). Turn on, all free for public repos:
   - Dependabot alerts and Dependabot security updates
   - Secret scanning and **push protection** (blocks a push that contains a key, before it's public)
   - Private vulnerability reporting
   - **CodeQL: leave "Default setup" off.** We use the workflow file ("advanced setup"); with default setup on as well, the workflow's uploads are rejected.
4. **Protect `main`.** Settings → Rules → Rulesets → New branch ruleset, name `main`, enforcement Active, target the default branch:
   - Restrict deletions; block force pushes
   - Require a pull request before merging, with **0** required approvals (so either of us can self-merge a small green PR near the deadline, as `agents/workflow.md` allows). Turn on "Dismiss stale approvals" only if you raise approvals to 1 later.
   - Require status checks to pass: `Analyze (actions)` now; add `Analyze (java-kotlin)` and `Analyze (javascript-typescript)` once they've run once. Add the build/test job here when CI exists.
   - Leave "Require review from Code Owners" **off** (the PR author can't approve their own PR).
   - Leave the bypass list empty, or add yourself as admin for emergencies only.
5. **General settings.** Allow squash merging only (one commit per PR keeps `main` readable), and keep "Automatically delete head branches" on (already on).
6. **About section.** Make sure the MIT license shows there (judging stage 1 checks it).

## Day to day

- Open a PR into `main` and CodeRabbit posts a summary and review within a few minutes; CodeQL runs as a check. Draft PRs and titles with `WIP` are skipped until they're ready.
- Commands, in a PR comment:
  - `@coderabbitai review` to review new commits now, `@coderabbitai full review` to start over
  - `@coderabbitai pause` / `@coderabbitai resume` for a noisy PR
  - `@coderabbitai resolve` to resolve all its comments once handled
  - Ask questions in any thread ("why is this a problem?", "write a test for this")
- If a CodeRabbit comment is wrong for this project, reply saying why. That becomes a learning, and the next review is better.
- Agents can fix review comments too: point Claude Code or Antigravity at the PR and ask it to address the CodeRabbit comments.
- **Splitting review work:** whoever didn't write the PR does a quick human look at the demo flow and the money rules; CodeRabbit and CodeQL cover the line-by-line pass. The "does `docker compose up` still work" check before merging is a natural fit for Robert as QA lead, if he wants it.
- Final polish week: if we want stricter reviews, change `profile: chill` to `assertive` in `.coderabbit.yaml`.
