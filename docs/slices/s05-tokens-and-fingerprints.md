# S5: Scoped tokens and message fingerprints

**Owner:** Matei · **Estimate:** 2 days · **Depends on:** S2, S4

## Goal

Desk-to-desk calls require a short-lived token from the registry, scoped to one receiver and one kind of action. Both sides log a fingerprint of every message to the registry, which never sees contents or prices. The registry can block an agent instantly.

## Demo moment

A 20-second security beat: a `curl` to the roaster's agent endpoint without a token gets 401; with a token for a different audience gets 403. Then the registry's audit page shows rows of hashes, sender, receiver, type and time, and no prices. Block a supplier, and its next quote never arrives.

## In scope

- **Registry as authorization server** (Spring Authorization Server): each VERIFIED business is an OAuth client with `private_key_jwt` client authentication using the key from S4. Token: JWT, about 5 minutes, `aud` = receiving desk's domain, scopes as in the table under Contracts.
- **Desks as resource servers** (Spring Security): `/agent/**` requires a valid registry-signed token, the right `aud`, and the scope for the envelope `type` from the table below; any other type is rejected. The envelope `from` must equal the token's client. Remove the `TODO S5` markers from S2.
- **Message signing:** the sender also signs the envelope (JWS, ES256) with nonce and expiry; the receiver checks it against the sender's registry-held key. This is what makes fingerprints provable.
- **Fingerprints:** after sending or receiving, each desk posts `{ messageId, conversationId, sha256, from, to, type, sentAt }` to the registry. Registry stores them; a stored message on either desk can be re-hashed and checked.
- **Block:** registry admin action sets `BLOCKED`; no new tokens are issued. Tokens already issued would live up to 5 minutes, so desks also pull `GET /api/blocked` every 10 seconds and reject any call whose client is on that list.
- Frontend: a "Network audit" AG Grid of fingerprints, and a "verify" button on a stored message.

## Out of scope

Key rotation UI (document the process; rotating is "publish a new key in the card, re-register"). mTLS.

## Contracts

- Token (plain HTTP inside compose only; any hosted deployment uses HTTPS): `POST http://registry.test/oauth2/token`, `grant_type=client_credentials`, `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, `scope`, and `resource=http://roaster.test` (RFC 8707) for the receiver. An `OAuth2TokenCustomizer` copies `resource` into the JWT's `aud` after checking it is a VERIFIED, non-blocked business.
- Scope per inbound message type. Responses (`quote` or `reject` to an RFQ, a revised `quote` to a counter, `invoice` to an accept) travel in the HTTP response of the same call and need no scope of their own.

  | Inbound type | Sender | Scope |
  | --- | --- | --- |
  | `rfq` | buyer | `rfq:send` |
  | `counter` | buyer | `quote:counter` |
  | `accept` | buyer | `quote:accept` |
  | `invoice.status` | supplier | `invoice:notify` |

- Envelope gains a `signature` field (compact JWS over the canonical envelope without `signature`).
- `POST /api/fingerprints`, `GET /api/fingerprints?conversationId=`.

## Data

Registry: `fingerprint` (message_id unique per side, conversation_id, sha256, from_domain, to_domain, type, sent_at, reported_by). Spring Authorization Server's client tables. No message bodies anywhere in the registry.

## Acceptance criteria

1. Missing, expired, wrong-audience and wrong-scope tokens are rejected with 401 or 403 (integration tests).
2. A valid token from supplier B can't be used to call the café as if it were the roaster (`from` mismatch rejected).
3. Blocking a supplier stops its token requests at once, and a token it got before the block is rejected within 10 seconds (test); the demo flow carries on with the others.
4. The registry database contains no prices or message bodies (check the schema and a dump after a full run).
5. Tampering with a stored message on one desk makes "verify" fail.
6. The full demo flow (S1 to S3) still works with tokens on, and adds under 1 second end to end.

## Review-sensitive

This is the slice the reviewer is most likely to change, which is why it comes after the demo spine.

- Spring Authorization Server vs a small hand-rolled token endpoint (less code, less credibility).
- Whether envelope signatures are needed on top of tokens, or tokens alone are enough for the hackathon.
- Fingerprint timing (sync on send vs batched).
