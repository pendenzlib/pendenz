# Status model

- Status: accepted
- Date: 2026-10-18

## Context

A task's status is stored as text in the user's status column. User
interfaces read it, SQL recipes set it, the library enforces it. It is the
central part of the schema contract and has to be settled before anything
else.

## Decision

Ten states in three groups. The group says who acts next.

| Status | Group | Meaning |
|---|---|---|
| `PENDING` | active | Due, waiting to be claimed |
| `SCHEDULED` | active | Run time in the future |
| `AWAITING_APPROVAL` | waiting for a human | Task type requires approval |
| `RUNNING` | active | Claimed, heartbeat running |
| `SUCCEEDED` | final | Result accepted |
| `RETRY_PENDING` | active | Failed, attempts left, backoff running |
| `FAILED` | waiting for a human | Attempts exhausted |
| `REJECTED` | waiting for a human | Result refused by validation |
| `QUARANTINED` | waiting for a human | Deliberately set aside, with a reason |
| `CANCELLED` | final | Will never run |

The legal transitions are defined in `TaskStatusTransitions` in the core and
nowhere else. Generated SQL functions and the documented SQL recipes apply
the same rules. Every row carries a version column for optimistic locking,
so an SQL intervention and a running worker never both win.

Rules the tests pin down:

- Final states have no outgoing transitions.
- Every non-final state can be cancelled.
- Only `PENDING` can be claimed.
- States waiting for a human can be restarted or marked done.

## Rejected alternatives

- **Fewer states, distinguished by extra columns.** User interfaces and SQL
  filters would then have to know column combinations instead of one value.
- **`REJECTED` as a variant of `FAILED`.** A retry does not help with a
  refused result; the handling is different.
- **No quarantine.** There would be no reversible way to hold a task back
  without cancelling it or burning its attempts.

## Consequences

- Status names are stable from 1.0 on. Renaming one breaks the schema API.
- `AWAITING_APPROVAL` and `REJECTED` are part of the model from v0.1, even
  though approval and validation only arrive in v0.3, so the contract does
  not change afterwards.
