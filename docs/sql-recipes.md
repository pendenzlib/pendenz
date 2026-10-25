# SQL recipes

Pendenz treats SQL as a supported interface. These statements are safe to run
against a task table while workers are active, because every one of them
guards on the current status and bumps the version column. A worker that
finishes a task after you changed it finds a different version and drops its
outcome instead of overwriting yours.

Replace `pendenz_task` and the column names with your mapping.

Every recipe below also exists as a generated function with a version
check, see [Generated functions](#generated-functions) at the end, and the
questions have generated views, see [Generated views](#generated-views).

## Enqueue a task

Payload fields live in the columns the task type declares, so an insert is
an ordinary insert. A JSON task type takes its document in the `payload`
column instead.

```sql
INSERT INTO pendenz_task (task_type, status, run_at, invoice_id, customer)
VALUES ('send-invoice', 'PENDING', now(), 4711, 'Globex');
```

The same shape creates a task per row of a query:

```sql
INSERT INTO pendenz_task (task_type, status, run_at, invoice_id, customer)
SELECT 'send-invoice', 'PENDING', now(), i.id, c.name
FROM invoice i JOIN customer c ON c.id = i.customer_id
WHERE i.sent_at IS NULL;
```

## Restart a failed task

```sql
UPDATE pendenz_task
SET status = 'PENDING', version = version + 1
WHERE id = 42 AND status IN ('FAILED', 'REJECTED', 'QUARANTINED');
```

The next poll claims it. `attempts` keeps counting, so the row tells the
whole story. Reset it to 0 if you want the retry policy to start over.

## Restart every failed task of one type

```sql
UPDATE pendenz_task
SET status = 'PENDING', version = version + 1
WHERE task_type = 'send-invoice' AND status = 'FAILED';
```

## Mark a task as done without running it

```sql
UPDATE pendenz_task
SET status = 'SUCCEEDED', finished_at = now(), version = version + 1
WHERE id = 42 AND status IN ('FAILED', 'REJECTED', 'QUARANTINED');
```

`finished_at` is what retention counts from; a final row without it is
retained from `created_at` instead.

## Cancel a task

Works in every non-final state, including a running one. A running handler is
cancelled at its next heartbeat and its result is discarded.

```sql
UPDATE pendenz_task
SET status = 'CANCELLED', finished_at = now(), version = version + 1
WHERE id = 42 AND status NOT IN ('SUCCEEDED', 'CANCELLED');
```

## Put a task aside for investigation

```sql
UPDATE pendenz_task
SET status = 'QUARANTINED', last_error = 'held back until ticket #123 is fixed', version = version + 1
WHERE id = 42 AND status IN ('PENDING', 'SCHEDULED', 'RETRY_PENDING', 'FAILED', 'REJECTED');
```

## Postpone a task

```sql
UPDATE pendenz_task
SET status = 'SCHEDULED', run_at = now() + interval '1 hour', version = version + 1
WHERE id = 42 AND status = 'PENDING';
```

## What is stuck?

Running tasks whose worker stopped sending heartbeats. Each claim and each
heartbeat writes the lock's deadline into `locked_until`; once it has passed,
the lock has expired and the next poll of a worker deals with the task.

```sql
SELECT id, task_type, locked_by, locked_until, attempts
FROM pendenz_task
WHERE status = 'RUNNING' AND locked_until < now();
```

## How far is a cycle?

The generated view, named after the cycle table with `_progress`, has
one row per cycle and a column per task status.

```sql
SELECT id, description, status, tasks, succeeded, pending, running, failed, quarantined, cancelled
FROM pendenz_cycle_progress
WHERE status IN ('ACTIVE', 'PAUSED')
ORDER BY created_at DESC;
```

## Pause a cycle

The cycle row changes status, its waiting tasks step aside as
`QUARANTINED` with the reason the library uses, and running tasks finish.
Resuming releases exactly the tasks that carry that reason. A cycle's
tasks share the lane `cycle:` plus the cycle's id.

```sql
UPDATE pendenz_cycle SET status = 'PAUSED', version = version + 1
WHERE id = 7 AND status = 'ACTIVE';

UPDATE pendenz_task
SET status = 'QUARANTINED', last_error = 'cycle paused', version = version + 1
WHERE lane = 'cycle:7' AND status IN ('PENDING', 'SCHEDULED', 'RETRY_PENDING');
```

## Resume a cycle

```sql
UPDATE pendenz_cycle SET status = 'ACTIVE', version = version + 1
WHERE id = 7 AND status = 'PAUSED';

UPDATE pendenz_task
SET status = CASE WHEN run_at > now() THEN 'SCHEDULED' ELSE 'PENDING' END,
    last_error = NULL, version = version + 1
WHERE lane = 'cycle:7' AND status = 'QUARANTINED' AND last_error = 'cycle paused';
```

## Cancel a cycle

```sql
UPDATE pendenz_cycle SET status = 'CANCELLED', finished_at = now(), version = version + 1
WHERE id = 7 AND status IN ('ACTIVE', 'PAUSED');

UPDATE pendenz_task
SET status = 'CANCELLED', locked_by = NULL, locked_until = NULL, finished_at = now(), version = version + 1
WHERE lane = 'cycle:7' AND status NOT IN ('SUCCEEDED', 'CANCELLED');
```

## Who is at their limit?

The limit table holds exact names or `LIKE` patterns; the longest match
wins. Running counts per resource value tell who is at the limit.

```sql
SELECT resource, count(*) AS running
FROM pendenz_task
WHERE status = 'RUNNING' AND resource IS NOT NULL
GROUP BY resource
ORDER BY running DESC;
```

## Change a limit while the mail server struggles

Effective at the next poll, on every worker, without a restart.

```sql
UPDATE pendenz_resource_limit SET max_running = 1 WHERE name = 'mail-server';

INSERT INTO pendenz_resource_limit (name, max_running) VALUES ('customer:%', 2)
ON CONFLICT (name) DO UPDATE SET max_running = EXCLUDED.max_running;
```

## Approve or decline a task

A task held by its type's approval rule waits as `AWAITING_APPROVAL`. The
approver is never the creator; the generated `_approve` function enforces
that, and the recipe does too.

```sql
UPDATE pendenz_task
SET status = CASE WHEN run_at > now() THEN 'SCHEDULED' ELSE 'PENDING' END,
    approved_by = 'manager', approved_at = now(), version = version + 1
WHERE id = 42 AND status = 'AWAITING_APPROVAL' AND created_by IS DISTINCT FROM 'manager';

UPDATE pendenz_task
SET status = 'CANCELLED', last_error = 'not this month', finished_at = now(), version = version + 1
WHERE id = 42 AND status = 'AWAITING_APPROVAL';
```

## The whole chain of a task

A handler's follow-ups carry the chain's correlation id and the id of the
task that spawned them.

```sql
SELECT id, task_type, status, parent_id, created_at
FROM pendenz_task
WHERE correlation_id = 'welcome-ada'
ORDER BY created_at;

SELECT id, task_type, status FROM pendenz_task WHERE parent_id = 42;
```

## What did a task produce, and what produced it?

A handler's result and the provenance it attached are JSON in the row;
the preview is what the task was going to do, computed when it was
enqueued.

```sql
SELECT id, status, preview, result, provenance ->> 'model' AS model, last_error
FROM pendenz_task
WHERE status IN ('REJECTED', 'AWAITING_APPROVAL')
ORDER BY created_at;
```

## What finished in the last hour, and how long did it take?

```sql
SELECT id, task_type, status, finished_at - created_at AS age, attempts
FROM pendenz_task
WHERE finished_at > now() - interval '1 hour'
ORDER BY finished_at DESC;
```

## Which payload versions are still in the table?

Tells you when an upgrade step in a task type can be retired.

```sql
SELECT task_type, payload_version, count(*)
FROM pendenz_task
GROUP BY task_type, payload_version
ORDER BY task_type, payload_version;
```

## Who is waiting, and how long is the line?

Lanes take turns at the workers, so the length of a lane tells how long
its newest task waits, not how long a single task waits.

```sql
SELECT task_type, lane, priority, count(*), min(run_at) AS waiting_since
FROM pendenz_task
WHERE status = 'PENDING'
GROUP BY task_type, lane, priority
ORDER BY priority DESC, count(*) DESC;
```

## Make a task urgent

Priority is a strict tier: as long as anything of a higher priority is
due, nothing below it runs.

```sql
UPDATE pendenz_task
SET priority = 10, version = version + 1
WHERE id = 42 AND status IN ('PENDING', 'SCHEDULED', 'RETRY_PENDING');
```

## Generated views

`PostgresOperatorDdl.views(table)` creates three views named after the
table. `pendenz_task_failures` groups failed tasks by type and error;
`pendenz_task_stuck` lists running tasks whose lock expired more than five
minutes ago (the period is a parameter); `pendenz_task_needs_human` lists
every task that waits for a decision.

```sql
SELECT * FROM pendenz_task_failures ORDER BY tasks DESC;
SELECT * FROM pendenz_task_stuck;
SELECT * FROM pendenz_task_needs_human ORDER BY created_at;
```

## Generated functions

`PostgresOperatorDdl.functions(table, cycleTable)` creates functions that
take the id and the version the caller saw, apply the legal transition
and return whether the row changed. A `false` means someone else got
there first, or the task was not in a state the operation applies to.

```sql
SELECT pendenz_task_restart(42, 3);
SELECT pendenz_task_complete(42, 3);
SELECT pendenz_task_quarantine(42, 3, 'held back until ticket #123 is fixed');
SELECT pendenz_task_cancel(42, 3);
SELECT pendenz_task_approve(42, 3, 'manager');
SELECT pendenz_task_decline(42, 3, 'manager', 'not this month');
SELECT pendenz_cycle_cancel(7, 1);
```

The allowed source states come from the library's transition table, so
the functions and the recipes above never disagree.

## What needs a human?

```sql
SELECT status, task_type, count(*)
FROM pendenz_task
WHERE status IN ('FAILED', 'REJECTED', 'QUARANTINED', 'AWAITING_APPROVAL')
GROUP BY status, task_type
ORDER BY status, task_type;
```
