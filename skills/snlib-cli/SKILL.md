---
name: snlib-cli
description: Run Seongnam Library (snlib.go.kr) tasks with the native MoonBit CLI: login, book search, account/loan/reservation status, interlibrary loans, hope-book requests, and basket queries. Prepare write requests first and submit only after explicit user confirmation.
metadata: { "openclaw": { "requires": { "bins": ["bash", "moon", "cc"] } } }
---

# snlib-cli (MoonBit)

Run `"{baseDir}/scripts/snlib-cli.sh" <command>` with exactly one JSON object
on stdin. Use `{}` for commands without fields. Clojure flags, EDN request
payloads, and `SNLIB_USER` / `SNLIB_PASSWORD` are not used by this launcher.

## Setup

Install MoonBit (`moon`), a native C compiler, and libcurl development files.
On macOS, Xcode Command Line Tools provide the compiler and system libcurl.
On Debian/Ubuntu, install `build-essential` and `libcurl4-openssl-dev`.

Inside this repository, the launcher finds `moonbit/` relative to itself.
For a separately installed skill bundle, obtain the source checkout:

```bash
git clone --branch moonbit https://github.com/ruseel/snlib-cli.git /path/to/snlib-cli
export SNLIB_MOONBIT_DIR="/path/to/snlib-cli/moonbit"
moon -C "$SNLIB_MOONBIT_DIR" update
```

Use an absolute path. Initialize a fresh dependency registry once with
`moon update`. The launcher builds/runs the native package; it does not
download the repository or fall back to Clojure. First builds require network
access for dependencies. Build diagnostics are separate from JSON results.

## Quick Start

```bash
"{baseDir}/scripts/snlib-cli.sh" --help

# Public search: no login required.
printf '%s\n' '{"keyword":"제2차 세계대전 발췌본","manage_codes":["MA"],"page":1,"per_page":10}' |
  "{baseDir}/scripts/snlib-cli.sh" search-books

# Login from a private JSON file with user_id and password (permissions 0600).
"{baseDir}/scripts/snlib-cli.sh" login < /path/to/private-login.json

printf '%s\n' '{}' | "{baseDir}/scripts/snlib-cli.sh" my-info
printf '%s\n' '{}' | "{baseDir}/scripts/snlib-cli.sh" loan-status
```

## Workflows

- Account/session: `login`, `my-info`.
- Discovery: `search-books`, `basket-list`.
- Account queries: `loan-status`, `loan-history`, `reservation-status`,
  `interloan-status`, `hope-book-list`, `hope-book-detail`.
- Request preparation/submission: `interloan-request` (alias
  `interlibrary-loan-request`) and `hope-book-request`.

Read `{baseDir}/references/commands.md` for JSON patterns.

## Safety and Results

- Inspect JSON `outcome` and `code`, not just the exit status. The native CLI
  currently exits successfully even for command-level errors.
- Write commands default to preparation only. Review `data.prepared_payload`
  and obtain explicit confirmation of the full request before setting
  `"submit":true`. Never infer SMS/contact consent from unchecked form defaults.
- Never automatically retry a POST after a network or persistence error. Check
  `interloan-status` / `hope-book-list` first; it may already have succeeded.
- Read login JSON from a private file outside this repository, with owner-only
  permissions (`0600`). Do not put real passwords in arguments, history, or logs.
  Login does not save the password.
- Account outputs and prepared payloads contain personal data. Avoid shared logs.
- Sessions live under `$XDG_CONFIG_HOME/snlib-cli`, falling back to
  `~/.config/snlib-cli`, and expire after three hours. Cookie refresh does not
  extend this limit. Legacy Clojure sessions remain untouched; log in once with
  the native CLI.
- Every authenticated command uses origin-bound cookies. Changing
  `SNLIB_BASE_URL` requires a new login. Use the default HTTPS service unless
  intentionally testing against a literal loopback server.

## Troubleshooting

- Source missing: set `SNLIB_MOONBIT_DIR` to the checkout's `moonbit/`.
- Build failure: check MoonBit, C compiler, and libcurl headers/library.
- Missing registry: run `moon -C "$SNLIB_MOONBIT_DIR" update`.
- JSON/input errors: pipe one JSON object; check `references/commands.md`.
- `session-missing`, `session-expired`, or `session-rejected`: log in again.
- `session-origin-mismatch`: log in for the selected server.
- `submit-failed`: the response did not confirm submission; inspect status before retry.
- `session-save-failed`: fix config-directory access; after a write, check status
  before retrying.
- HTTP/parse errors: do not claim success; the website may have changed.

Source: https://github.com/ruseel/snlib-cli
