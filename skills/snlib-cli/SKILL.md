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
./scripts/snlib-cli login --pretty

# common read-only checks
./scripts/snlib-cli my-info --pretty
./scripts/snlib-cli loan-status --pretty
./scripts/snlib-cli search-books --keyword "제2차 세계대전 발췌본" --pretty
```

## Git Reference Policy (Tag Only)

This launcher resolves the CLI directly from a GitHub repository via Clojure git deps.

- It uses `:git/url` + `:git/tag`.
- It does **not** use SHA refs.
- It does **not** fall back to `main`/default branch when tag is missing.
- If `SNLIB_GIT_TAG` is empty, launcher exits with an error.

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

The launcher supports env var overrides for the target GitHub repository and tag:

- `SNLIB_GITHUB_REPO` (default `https://github.com/ruseel/snlib-cli`)
- `SNLIB_GIT_TAG` (default `v0.1.0`)

```bash
SNLIB_GIT_TAG=v0.2.0 ./scripts/snlib-cli my-info --pretty
SNLIB_GITHUB_REPO=https://github.com/example/snlib-cli SNLIB_GIT_TAG=v0.2.0 ./scripts/snlib-cli loan-status --pretty
```

## Troubleshooting

If authentication fails:

1. Run `./scripts/snlib-cli login --pretty`
2. Check `~/.config/snlib-cli/credentials.edn`
3. Retry your target command

If dependency resolution fails, verify repository URL/tag and network access.
