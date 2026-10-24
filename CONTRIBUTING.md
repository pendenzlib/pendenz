# Contributing

Thank you for taking the time.

## Issues

Issues are welcome: bugs, questions, missing features, unclear
documentation. For a bug, say which version or commit, which database
version, what you did, what you expected and what happened; a failing
test or a few lines of SQL are the best report.

## Pull requests

Pull requests only after agreement. Open an issue first and describe the
change; once it is agreed there, a pull request is welcome. Unannounced
pull requests may be closed without review, however good they are, since
every change has to fit the principles in the [README](README.md) and
the decisions in [`docs/adr`](docs/adr).

An agreed pull request follows the working rules in
[`CLAUDE.md`](CLAUDE.md), which apply to humans and AI tools alike:

- `./gradlew build` is green: ktlint, detekt, tests, API check.
- Every change in behaviour comes with a test, written to the test
  conventions there.
- Commits follow Conventional Commits with the module as scope, one
  intention per commit.
- A fundamental decision is proposed as an ADR before it is implemented.

## Security

Do not report vulnerabilities in public issues; see
[`SECURITY.md`](SECURITY.md).
