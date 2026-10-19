# SQL recipes

Pendenz treats SQL as a supported interface. These statements are safe to run
against a task table while workers are active, because every one of them
guards on the current status and bumps the version column. A worker that
finishes a task after you changed it finds a different version and drops its
outcome instead of overwriting yours.

Replace `pendenz_task` and the column names with your mapping.

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

Running tasks whose worker stopped sending heartbeats. Pendenz releases them
automatically after the lock timeout; this shows them before that happens.

```sql
SELECT id, task_type, locked_by, locked_at, attempts
FROM pendenz_task
WHERE status = 'RUNNING' AND locked_at < now() - interval '2 minutes';
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

## What needs a human?

```sql
SELECT status, task_type, count(*)
FROM pendenz_task
WHERE status IN ('FAILED', 'REJECTED', 'QUARANTINED', 'AWAITING_APPROVAL')
GROUP BY status, task_type
ORDER BY status, task_type;
```
