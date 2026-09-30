# MoonBit command implementation TODOs

Work branch: `moonbit`. Preserve the existing launcher migration and review fixes.
Use the Clojure implementation as the endpoint/payload contract reference.
Validate offline; never use real credentials or submit to the live library.

## Tasks

- [x] Integrate native HTTP transport, cookie sessions, login, search, and my-info from main.
- [x] Preserve non-destructive legacy handling, safe TTL, catalogue IDs, validation, and fsync cleanup.
- [x] Add typed result models and JSON rendering for all account queries.
- [x] Implement loan-status (including optional history), loan-history, and reservation-status.
- [x] Implement interloan-status.
- [x] Implement hope-book-list and hope-book-detail.
- [x] Implement basket-list (default group and explicit group).
- [x] Implement interloan-request and its alias with preparation and explicit submission evidence.
- [x] Implement hope-book-request with defaults, validation, and explicit submission evidence.
- [x] Ensure every authenticated command checks origin, detects rejection, and persists refreshed cookies.
- [x] Add offline HTTP/fixture tests for every command, validation, rejection, and failure paths.
- [x] Update skill/docs to describe live capabilities and write-operation confirmation.
- [x] Run formatting, API inspection, native checks/build/tests, and all launcher/regression suites.

## Progress

- Initial plan recorded; retained the prior launcher migration and review fixes.
- Integrated the native libcurl adapter, login/search/my-info, and v2 cookie sessions from main without switching branches or discarding worktree changes.
- Added typed models/rendering and HTML/remote/application layers for every remaining account query.
- Added explicit `submit` flags (default false), scoped hope-book form extraction, field/default validation, book-identity checks, and command-specific confirmation for writes.
- Generalized authenticated origin/session handling and cookie refresh to all account/request commands. Removed executable `not-implemented` fallthrough; pure calls without a transport report `transport-unavailable`.
- Added fictional fixtures and end-to-end local HTTP contracts, including validation, both loan layouts, empty lists, basket's second stage, cookie rotation/rejection, dry runs, confirmed submissions, and POST redirect refusal.
- Updated README, the skill bundle, and `docs/moonbit-commands.md`.
- Final validation passed: `moon fmt`, native `check` / `build` / `info`, and `git diff --check`, with no compiler warnings.
- Tests passed: **50 MoonBit + 28 HTTP + 17 command-contract + 4 native regression + 6 launcher = 105 named tests**, plus parameterized subcases.
- Generated `pkg.generated.mbti` interfaces were reviewed and retained as the public API snapshot.
- No real credentials or live library requests were used.

## Operational limitations

- Live website compatibility has not been verified with a real account. The implementation follows the Clojure endpoint contracts and supplied/synthetic HTML fixtures; site changes may require extractor updates.
- List commands return the current page, not automatic pagination across an entire account.
- Inspect JSON outcomes: command-level errors currently retain a zero native process exit status.
- After a write network/persistence error, query request status before retrying. Do not automatically repeat submissions.
- A remote skill checkout can use these changes only after this implementation is pushed to the `moonbit` branch.
