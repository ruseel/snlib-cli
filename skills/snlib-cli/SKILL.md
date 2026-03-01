---
name: snlib-cli
description: Run Seongnam Library (snlib.go.kr) workflows via Clojure CLI without bundling an uberjar. Use when you need login, book search, loan status, interlibrary loan request/status, hope-book request/list/detail, or basket queries from a lightweight Maven artifact + `clojure -M -m snlib.cli` launcher.
---

# snlib-cli

Use this skill to execute the Seongnam Library CLI from a Maven artifact (small distribution, no fat jar).

## Quick Start

1. Ensure Java and `clojure` are installed.
2. Run `scripts/snlib` from this skill directory.
3. Login once, then run commands normally.

```bash
# first-time login (credentials are loaded/saved by snlib.cli)
./scripts/snlib login --pretty

# search
./scripts/snlib search-books --keyword "제2차 세계대전 발췌본" --pretty

# mypage
./scripts/snlib my-info --pretty
./scripts/snlib loan-status --pretty
./scripts/snlib interloan-status --pretty
```

## Runtime Model (A안)

Always run via Maven dependency resolution at execution time:

- Command shape: `clojure -Sdeps ... -M -m snlib.cli ...`
- Do not build/use uberjar in this skill.
- Keep launcher tiny; keep app updates in Maven artifact versions.

## Versioning Rules

- Pin a default artifact version in `scripts/snlib`.
- Allow override using env vars:
  - `SNLIB_GROUP` (default `io.github.ruseel`)
  - `SNLIB_ARTIFACT` (default `snlib-cli`)
  - `SNLIB_VERSION` (default `0.1.0`)
- Override per command when needed:

```bash
SNLIB_VERSION=0.2.0 ./scripts/snlib my-info --pretty
```

## Operational Safety

- Use read-only commands first (`my-info`, `loan-status`, `interloan-status`, `search-books`).
- For write operations (`interloan-request`, `hope-book-request`), require explicit submit flags:
  - `--submit --allow-submit`
- Keep credentials/session in `~/.config/snlib-cli/`.

## Troubleshooting

If `clojure` is missing, install Clojure CLI first.

If authentication fails:

1. Run `./scripts/snlib login --pretty`
2. Re-check `~/.config/snlib-cli/credentials.edn`
3. Re-run target command

If artifact resolution fails, verify GAV/version and repository access.

## References

Read `references/commands.md` for command patterns and common flows.