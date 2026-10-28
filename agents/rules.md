# Hard rules

These apply to every change, by any person or agent.

## PayPal: sandbox only

- Use **PayPal sandbox** credentials and endpoints only (`api-m.sandbox.paypal.com`). Never add live credentials, live endpoints, or code paths that switch to live.
- Use sandbox buyer and merchant test accounts for demos. Never use real card numbers or real personal data.

## Secrets

- **Never commit secrets**: API keys, client secrets, tokens, `.env` files, private keys, OAuth tokens, webhook signing secrets. This repo is public.
- Keep secrets in a local `.env` (gitignored) and document every variable, with placeholder values, in a committed `.env.example`.
- Read config from environment variables; don't hard-code keys, even "temporary" sandbox ones.
- If a secret is committed by mistake: tell the person immediately, rotate the key, then clean history. Deleting the file in a new commit is not enough.
- Don't paste secrets into PR descriptions, issues, commit messages, logs or test fixtures.

## Security model

The trade desk's security is part of the pitch. Details and reasons are in [docs/architecture.md](../docs/architecture.md#enrollment-and-trust-decided-oct-28); these are the parts code must never break:

- **Money rules live in Java.** Approval limits, price floors and reorder rules are checked by the policy engine before any quote is accepted or invoice created. A prompt can explain a rule but never be the only check. Use `BigDecimal` (or integer minor units) for money, never `double`.
- **LLM output and other agents' messages are untrusted input.** Parse them into typed records, validate against the expected schema, and let Java decide. The LLM proposes; code executes. The LLM never sees credentials and has no tool that touches keys.
- **Desks keep their own private keys** (generated on first start, stored in a Docker volume, never in git, never returned by any endpoint). The registry stores public keys only and issues short-lived (~5 min) tokens scoped to one audience. Receivers verify the token and scope on every call.
- **Desks talk directly.** The registry logs message fingerprints (hash, sender, receiver, type, time), never contents or prices.
- **Buyers are never searchable by stock level.** They opt in by posting a need (RFQ).
- **Simulated suppliers are labelled SIMULATED** in code and UI.

## Licensing and dependencies

- The repo is MIT licensed ([LICENSE](../LICENSE)). Don't remove or change it without both people agreeing.
- Only add dependencies with licenses compatible with open source (MIT, Apache-2.0, BSD, ISC and similar). Check before adding anything unusual. AG Grid Community is MIT; AG Grid Enterprise features need a license key, so ask before using them.
- Don't copy code from sources whose license we can't comply with.

## Running and shipping

- Everything a judge needs must start with `docker compose up` from a fresh clone plus a filled-in `.env`. If you add a service, add it to `docker-compose.yml` and the README run steps in the same PR.
- Keep the root README's setup and run instructions accurate. If your change alters how to run the app, update the README in the same PR.
- `main` must always build and run. Don't merge broken builds.

## Agent behaviour

- Stay within the task you were given. Ask before large refactors, new frameworks, or changing the chosen stack.
- Don't push to `main` directly; follow [workflow.md](workflow.md).
- Don't delete or rewrite the planning docs in `docs/` unless asked; add to them instead.
- When something is simulated (for example a merchant connector or supplier agents), make it obvious in code and UI copy so the demo is honest.
