# Jackson codec as a module

- Status: accepted
- Date: 2026-10-19

## Context

The core encodes payloads with kotlinx-serialization, which needs
`@Serializable` Kotlin classes. Java teams, half of the audience, have
Jackson and records. Without a codec for them, every Java user writes a
`PayloadCodec` by hand, as the Java facade's own tests had to.

Jackson exists in two lines: 2.x under `com.fasterxml.jackson`, still
maintained, and 3.x under `tools.jackson`, the line Spring Boot 4 and
Spring Framework 7 ship with. The two have different packages and cannot
be served by one artifact.

## Decision

- A module `pendenz-serialization-jackson` with `JacksonCodec`, a
  `PayloadCodec` over an `ObjectMapper` the application supplies, and
  `JacksonPayloadUpgrade`, which rewrites a stored payload as an
  `ObjectNode` for ADR 0010.
- The module depends on Jackson 3 (`tools.jackson.core:jackson-databind`).
  It is the line the rest of the baseline in ADR 0003 points to, and the
  one a new project in 2026 starts on.
- The core stays free of Jackson. Users on Jackson 2 write the same
  fifteen lines against `com.fasterxml`, or a `pendenz-serialization-jackson2`
  module appears when someone asks for it.

## Rejected alternatives

- **Jackson 2.** The larger installed base today; Spring Boot 4 and new
  projects move to Jackson 3. A library that is released in 2026 and meant
  to be used with Spring Boot 4 should not pull Jackson 2 in beside
  Jackson 3.
- **Both lines in one module with optional dependencies.** Two code paths,
  reflection or classpath sniffing to choose, and a module that compiles
  against two packages that look alike. Not worth it before anyone asks.
- **Jackson in the core as an optional dependency.** Principle 5, small
  core; a serialization choice is exactly what modules are for.

## Consequences

- One more module to publish, with its own API dump.
- The Java facade's tests keep their hand-written codec on purpose: they
  prove the core works without any serialization library.

Amended on review (2026-10-25): payloads come from a table that operators
and other services write too, so `JacksonCodec.of` refuses a mapper with
default typing, which would let a stored document name any class to
instantiate. `JacksonPayloadUpgrade` takes mapper and column before the
rewrite, like `PayloadUpgrade.json`, as `json` for objects and `node`
for any document; an upgrade step that fails, here or in the core, ends
the task as an unsupported payload version, without retries.
