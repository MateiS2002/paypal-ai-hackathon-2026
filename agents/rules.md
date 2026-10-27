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
