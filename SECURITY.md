# Security policy

## Supported versions

Nothing is released yet. Until 1.0, fixes go into the newest release
only.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub: on the
repository's **Security** tab, choose **Report a vulnerability**. Do not
open a public issue.

Describe the problem, the affected version or commit and, if you can, how
to reproduce it. You will get an answer within a week.

Pendenz builds SQL from the table and column names the application
configures and passes values as parameters. Reports about SQL injection, about permission
checks that can be bypassed (ADR 0018), and about what an MCP client can
reach beyond the task types the application exposes (ADR 0021) are
especially welcome.
