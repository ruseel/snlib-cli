---
name: snlib-cli
description: Run Seongnam Library (snlib.go.kr) tasks from the command line. Use when you need login, book search, my-info/loan status checks, interlibrary loan request/status, hope-book request/list/detail, or basket queries.
---

# snlib-cli

Use this skill to run Seongnam Library commands quickly from the CLI.

## Quick Start

1. Ensure Java and `clojure` are installed.
2. From this skill directory, run login once.
3. Run read-only commands first to verify your session.

```bash
# first-time login
./scripts/snlib login --pretty

# common read-only checks
./scripts/snlib my-info --pretty
./scripts/snlib loan-status --pretty
./scripts/snlib search-books --keyword "제2차 세계대전 발췌본" --pretty
```

## Common Workflows

- Account/session: `login`, `my-info`
- Discovery: `search-books`, `basket`
- Status checks: `loan-status`, `interloan-status`, `hope-book-list`, `hope-book-detail`
- Requests (write actions): `interloan-request`, `hope-book-request`

Read `references/commands.md` for command patterns and end-to-end flows.

## Safety Rules

- Start with read-only commands before any write action.
- Write actions require explicit confirmation flags:
  - `--submit --allow-submit`
- Credentials and session data are stored under `~/.config/snlib-cli/`.

## Optional Advanced Configuration

The launcher supports env var overrides when you need a different artifact target:

- `SNLIB_GROUP` (default `io.github.ruseel`)
- `SNLIB_ARTIFACT` (default `snlib-cli`)
- `SNLIB_VERSION` (default `0.1.0`)

```bash
SNLIB_VERSION=0.2.0 ./scripts/snlib my-info --pretty
```

## Troubleshooting

If authentication fails:

1. Run `./scripts/snlib login --pretty`
2. Check `~/.config/snlib-cli/credentials.edn`
3. Retry your target command

If artifact resolution fails, verify artifact coordinates/version and network/repository access.
