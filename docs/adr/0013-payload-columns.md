# Payload columns instead of one JSON column

- Status: proposed
- Date: 2026-10-19

## Context

A payload is one `jsonb` column. That is enough to run tasks, and it is
not what the positioning promises. "Tasks are part of the business data
model" means an operator writes `WHERE invoice_id = 42`, a UI shows the
invoice number without parsing JSON, a foreign key keeps a task from
outliving its invoice, and a row can be inserted with plain SQL without
assembling a JSON document by hand. Today each of these needs
`payload->>'invoiceId'`, a cast, and knowledge of the JSON layout.

Two things make this urgent rather than nice. First, v0.2 creates tasks
for a whole dataset with `INSERT ... SELECT` from a query the user
writes. With one JSON column that query must build the document in SQL,
`jsonb_build_object('invoiceId', i.id)`, which is the opposite of
"the schema is yours". Second, the payload codec is the one part of the
API that every handler touches; changing it after 0.1 is released costs
more than changing it now.

PostgreSQL offers a cheap partial answer: a generated column
`invoice_id bigint GENERATED ALWAYS AS ((payload->>'invoiceId')::bigint) STORED`
gives queries, indexes and UI columns without any library support.
It does not give SQL inserts or `INSERT ... SELECT`, because the JSON
column stays the one that must be written.

## Decision

A task type maps its payload onto columns, and JSON becomes one way of
doing that rather than the only way.

- `PayloadMapping<P>` replaces `PayloadCodec<P>` as the thing a task type
  carries. It declares its columns, each with a name and a SQL type, and
  converts a payload to column values and back:

  ```kotlin
  public interface PayloadMapping<P : Any> {
      public val columns: List<PayloadColumn>              // name, SqlType
      public fun write(payload: P): Map<String, Any?>      // column -> value
      public fun read(row: PayloadRow): P                  // row.get("invoice_id", Long::class)
  }
  ```

- `TaskType.json<P>("send-invoice")` stays the default and maps onto a
  single `jsonb` column named `payload`. Nothing changes for applications
  that are happy with JSON. `PayloadCodec` survives as the inner part of
  that mapping, so the Jackson module keeps its shape.
- A columns mapping lists the fields explicitly:

  ```kotlin
  val sendInvoice = TaskType.columns<SendInvoice>("send-invoice") {
      column("invoice_id", SqlType.BIGINT, { it.invoiceId }) { invoiceId = it }
      column("currency", SqlType.TEXT, { it.currency }) { currency = it }
  }
  ```

  The exact builder shape is for the implementation to settle; the
  decision is that fields map to columns by name and type, with no
  reflection and no annotation processing (working rules of this
  repository).
- Columns are declared per task type, not per table. Several types share
  one table; a column a type does not use stays `NULL` for its rows.
  `PostgresDdl.createTable(table, types)` emits the union of the mapped
  columns, and a type may use the same column as another when the name
  and type agree.
- `TaskColumns.payload` disappears from the fixed mapping. The fixed
  columns are what the queue needs for every row; the payload columns
  are what the task type needs.
- `TaskRecord.payload` becomes `Map<String, Any?>`, column name to value.
  The stores read every payload column of every registered type on claim
  and hand the row to the type's mapping.
- Payload versioning (ADR 0010) keeps its column and its rule; a
  `PayloadUpgrade` rewrites the column map instead of a text. The JSON
  helper keeps working on a `JsonObject` for the JSON mapping.
- Mixed mappings are allowed: a type may put `invoice_id` in a column and
  everything else in a `jsonb` column. That is the usual shape in
  practice, keys and foreign keys as columns, the rest as a document.

## Rejected alternatives

- **Generated columns, documented as a recipe, no library change.**
  Covers reads, indexes and UI. Does not cover writing rows with SQL or
  `INSERT ... SELECT`, so cycles in v0.2 would still build JSON in SQL.
  Worth documenting anyway for teams that stay on JSON.
- **Projection columns the library writes beside the JSON.** A mapping
  declares extractors, the insert writes both, reads still use JSON.
  Cheap, additive, and it creates two copies of the same fact with the
  JSON as the real one. An operator who fixes `invoice_id` with an
  `UPDATE` fixes nothing. Principle 6, leaving costs nothing, wants the
  columns to be the data, not a cache of it.
- **Columns derived by reflection from the payload class.** Pleasant in
  the happy path, and exactly the kind of magic the working rules
  exclude from the core: no reflection, no annotation processing. The
  explicit builder is a few lines per type.
- **Leaving it until a user asks.** The codec is the API surface every
  handler depends on; cycles depend on it; a change after 0.1 is a
  migration for everyone.

## Consequences

- The largest change since the status model: `TaskType`, both stores,
  the dialect, the DDL generator, the in-memory store, versioning and
  the Jackson module all touch it. Before 0.1 is released, so no
  migration for anyone.
- The schema contract (principle 8) gains a clear line: fixed columns
  are the queue's, payload columns are the task type's, both are the
  application's to name.
- The claim statement returns a wider row, one column per mapped payload
  field across all registered types. For tables with many types that is
  a few dozen columns, which is fine.
- SQL recipes gain an insert example and `INSERT ... SELECT` becomes the
  natural shape for cycles.

## Open questions for the discussion

1. Do we do this before 0.1 or as the first step of 0.2? The argument
   for before: no released API to break. The argument for after: 0.1
   ships sooner and cycles are the first real consumer.
2. Which SQL types does the first version support? Proposal: `BIGINT`,
   `INTEGER`, `TEXT`, `BOOLEAN`, `NUMERIC`, `TIMESTAMPTZ`, `DATE`,
   `UUID`, `JSONB`. Everything else waits for a request.
3. Does a column mapping require every column to be `NOT NULL` for its
   type, or may a payload field be optional? Proposal: optional fields
   map to nullable columns; the DDL marks nothing `NOT NULL` except the
   queue's own columns.
