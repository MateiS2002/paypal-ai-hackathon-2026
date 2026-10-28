# S4: Landing pages, agent cards, registry

**Owner:** Robert · **Estimate:** 2 days · **Depends on:** S0

## Goal

Each fictional company has a landing page on its own domain that also serves its A2A agent card. A business joins the network by registering its domain; the registry fetches the card from that domain, makes the desk sign a challenge, and only then lists it. The café finds suppliers through the registry instead of S2's static peer list.

## Demo moment

The roaster's landing page at `roaster.test`, then its agent card JSON. In the network screen, "Join network" for a new supplier: the row goes from PENDING to VERIFIED with a tick, and a fake supplier claiming someone else's domain fails the check.

## In scope

- **`web/`:** one static landing page per company (HTML/CSS, a little JS), all served by one nginx with server blocks for `cafe.test`, `roaster.test`, `supplier-b.test`, `supplier-c.test`, `supplier-d.test`. Compose network aliases make the names resolve inside Docker. SIMULATED suppliers say so in their footer.
- **Agent card:** nginx proxies `/.well-known/agent-card.json` on each domain to that company's desk, which renders it with its live public key (JWKS). The landing page itself stays static.
- **Desk identity (in `trade-desk`, package `identity`):** on first start generate an EC P-256 key pair, store it in a per-desk Docker volume, never log or return the private key. Endpoints: `GET /.well-known/agent-card.json`, `POST /agent/challenge` (signs the nonce).
- **Registry:** `POST /api/registrations { "domain" }` fetches the card, sends a nonce with `aud` = `registry.test` (the same exact value in the request, the JWS claim and the tests) and a 60 s expiry, verifies the signature against the card's JWKS, stores the business, its public keys, skills, delivery areas and catalogue summary.
- **Safe fetching:** the registry only fetches `http://<domain>/.well-known/agent-card.json` and the challenge URL on that same host. `domain` must be a bare host name (no scheme, port, path or IP literal) and, while we run under compose, must match an allowlist pattern (`*.test`, from config). No redirects, 2 s connect and read timeouts, 64 KB response cap. The card's `url` must be on the registered domain, or registration fails. A hosted deployment swaps the allowlist for HTTPS-only plus rejection of private, loopback and link-local addresses checked at connect time.
- **Search v1 (Postgres):** `GET /api/suppliers?q=&deliversTo=` with keyword match and a simple delivery-area check (list of country or region codes). This stays as the fallback when S6 adds Elasticsearch.
- Café desk: replace `DESK_PEERS` with a registry lookup; keep the static list behind a flag for offline debugging.
- Frontend: a "Network" page listing verified businesses (AG Grid).

## Out of scope

Tokens, scopes, fingerprints (S5). Semantic or geo-shape search (S6). Real TLS: inside compose we use plain HTTP and say so in the README; the trust check is the signature, not TLS.

## Contracts

Agent card (shape follows A2A; exact fields checked against the A2A spec when building):

```json
{
  "name": "Roaster & Co",
  "url": "http://roaster.test/agent",
  "description": "Specialty coffee roaster",
  "skills": [{ "id": "quote", "name": "Quote green and roasted coffee" }],
  "jwks": { "keys": [{ "kty": "EC", "crv": "P-256", "kid": "...", "x": "...", "y": "..." }] },
  "x-trade-desk": { "simulated": false, "deliversTo": ["US-CA", "US-NV"], "catalogueUrl": "http://roaster.test/agent/catalogue" }
}
```

Challenge: registry sends `{ "nonce", "aud": "registry.test", "exp" }`; desk returns a compact JWS (ES256) over it.

Registry API: `POST /api/registrations`, `GET /api/registrations/{id}`, `GET /api/suppliers`, `GET /api/businesses`.

## Data

Registry: `business` (domain unique, name, status `PENDING | VERIFIED | FAILED | BLOCKED`, simulated, verified_at), `business_key` (business_id, kid, jwk, active), `catalogue_entry` (business_id, sku, description, origin, unit, delivers_to).

## Acceptance criteria

1. Each `*.test` page loads in the browser through the documented host port, and each card is valid JSON with a public key only.
2. Registering `roaster.test` ends VERIFIED; a desk that serves another domain's card but can't sign with its key ends FAILED.
3. Registering `localhost`, an IP address, `postgres` or a domain outside the allowlist is refused before any request is made (test).
4. A replayed signature (same nonce, or wrong audience, or expired) is rejected (unit tests).
5. The private key file is in a volume, not in the image or git, and no endpoint or log line contains it.
6. Restarting a desk keeps its key; deleting the volume makes a new one and re-registration picks it up.
7. With S2 merged, the café's fan-out uses the registry's supplier list and the demo flow still works.

## Review-sensitive

- Card field names: we follow A2A where it has a field and put our extras under `x-trade-desk`.
- Whether the card is served by the desk (proxied) or written to a shared volume for nginx.
- Key type (EC P-256 vs Ed25519). P-256 has the widest Spring Security and Nimbus support.
