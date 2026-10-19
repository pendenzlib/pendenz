# Payload versioning

- Status: accepted
- Date: 2026-10-19

## Context

Payloads outlive deployments. A task enqueued today with
`{"invoiceId": 42}` may be claimed next week by a handler whose payload
class has since gained a required `currency` field. Without a version the
codec fails with a parse error, the retry policy retries a problem that no
retry can fix, and the task ends up `FAILED` with a message about JSON.
Scheduled and retried tasks make this common, not exotic.

## Decision

- A task type carries a `version`, an integer starting at 1. Every row
  stores the version of the type that enqueued it in a `payloadVersion`
  column, `integer NOT NULL DEFAULT 1`.
- A task type may register `upgrades`: one `PayloadUpgrade` per step from
  version `n` to `n + 1`, working on the stored column values, not on the
  payload class, because the old class may no longer exist. Before the
  mapping decodes a payload of an older version, the registry applies the
  upgrade chain up to the type's version. (Originally "the stored text";
  since ADR 0013 the stored form is a map of column values, and an
  upgrade rewrites that map.)
- `PayloadUpgrade.json { ... }` is a helper for the default JSON mapping:
  it parses the document in the `payload` column to a `JsonObject`, hands
  it to the lambda and writes the result back into that column.
- A payload whose version has no upgrade path, older with a gap or newer
  than the handler, fails the task immediately with
  `UnsupportedPayloadVersionException`, bypassing the retry policy: no
  retry fixes a version mismatch. The row says which version it carries
  and which the handler supports; an operator restarts it after the
  deployment that can handle it has gone out.
- A version newer than the handler is a deployment-order problem, not a
  runtime one. The rule is the usual one: deploy the handler that
  understands version `n + 1` before anything enqueues version `n + 1`.
  Filtering such tasks out of the claim would hide the mistake; failing
  them makes it visible at once.

## Rejected alternatives

- **Upgrades on decoded objects.** Needs every historical payload class
  on the classpath, forever.
- **Lenient decoding only** (ignore unknown keys, defaults for new ones).
  Covers additions, not renames, splits or semantic changes, and leaves
  no trace of which version a row carries.
- **A separate type name per version** (`send-invoice-v2`). Every
  producer must know the current name and every consumer must register
  every name; the version column records the same fact without that.
- **Skipping tasks that are newer than the handler.** Keeps them pending
  until a newer worker appears, which may be never, and hides a rollout
  mistake behind a growing backlog.

## Consequences

- One more column, one more constructor parameter on `TaskType`, one
  new exception type.
- Handlers can be deployed with upgrades for every version still in the
  table, and the versions can be retired once no row carries them any
  more; a query on the column tells when.
