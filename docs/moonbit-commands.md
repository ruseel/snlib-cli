# Native MoonBit commands

The native CLI executes all advertised commands. Input is exactly one JSON object
on stdin; output is one JSON envelope with `outcome`, `code`, `message`, and
typed `data`. Field names use snake_case. Inspect the envelope: command failures
currently do not set a nonzero process exit code.

Run via `skills/snlib-cli/scripts/snlib-cli.sh` in a repository checkout, or set
`SNLIB_MOONBIT_DIR` to an absolute path to `moonbit/` for a separately installed
skill. Requires MoonBit, a C compiler, and libcurl headers/library.
Run `moon -C moonbit update` once for a fresh registry.

## Authentication and discovery

- `login`: `{"user_id":"YOUR_ID","password":"YOUR_PASSWORD"}`. Feed credentials
  from a private file (0600), not arguments or shell history. Login is confirmed
  with an independent account-page request. Passwords are not saved.
- `search-books`: `{"keyword":"키워드"}`. Optional `manage_codes` (array),
  `page`, `per_page`, `sort`, and `order`. Pagination must be positive.
  Public search does not inherit account cookies.

## Account queries

All require a native cookie-bearing session:

| Command | Input | Result data |
| --- | --- | --- |
| `my-info` | `{}` | Patron/account fields |
| `loan-status` | `{"include_history":false}` | `loans`, `count`; excludes returned items by default |
| `loan-history` | `{}` | `loans`, `count` |
| `reservation-status` | `{}` | `reservations`, `count` |
| `interloan-status` | `{}` | `requests`, `count` |
| `hope-book-list` | `{}` | `items`, `count`; each item includes `rec_key` |
| `hope-book-detail` | `{"rec_key":"12345"}` | Applicant/book/request fields; missing optional fields are null |
| `basket-list` | `{}` or `{"group_key":"99999"}` | Group metadata, `books`, `count` |

`rec_key` and `group_key` are nonempty numeric strings, not JSON numbers.
List totals reflect the server's total when supplied; commands return the
current page, not an automatic fetch of every page. Loan status returns the
number of items after optional returned-item filtering.

## Write commands: prepare, confirm, submit

**Omitting `submit` or setting it to false performs preparation only.**
It fetches defaults but does not POST a library request. Result data includes
`prepared_payload`, `submit_attempted:false`, and `result_message:null`.

Before submission, obtain explicit confirmation of the book, receiving library,
and any personal/contact/consent fields. Then invoke the same command/input with
`"submit":true`. Do not automatically retry submissions after a network or
persistence error: the server may already have accepted the request. Check
`interloan-status` or `hope-book-list` first.

### Interloan

```json
{"manage_code":"MG","reg_no":"BOOK_REGISTRATION","apl_lib_code":"141484","submit":false}
```

`interlibrary-loan-request` is an alias. Required fields: two-letter
`manage_code`, nonblank `reg_no`, and six-digit `apl_lib_code`.
`give_lib_code` and `user_key` default to the preparation form.
`appendix_apply_yn` defaults to `"N"` and accepts `"Y"` or `"N"`.
Preparation verifies that the returned form is for the requested book.

### Hope book

```json
{
  "manage_code": "MU",
  "submit": false,
  "request": {
    "title": "도서명",
    "author": "저자",
    "publisher": "출판사",
    "publishYear": "2026",
    "eaIsbn": "9781234567890",
    "price": "30000",
    "email": "reader@example.invalid",
    "handPhone": "010-1234-5678",
    "smsReceiptYn": "Y"
  }
}
```

An empty request can inspect form defaults. Submission requires title, author,
publisher, valid manage code, contact email, valid phone, and explicit
`smsReceiptYn:"Y"` consent. Price, if supplied, must be digits.
The native command extracts only the hope-book form, preserves hidden defaults,
respects checked controls, and handles textarea/select values.
Snake/hyphen aliases such as `publish_year`, `hand_phone`, and
`sms_receipt_yn` are accepted inside `request`.
An explicit phone replaces all three component fields; it is never silently
replaced by saved defaults when invalid.

Successful POSTs require explicit command-specific success evidence. Unknown
HTTP 200 responses or rejection messages yield `submit-failed`.
POST redirects are never followed and POST requests are never automatically
retried. Fixed submit paths prevent page-controlled actions from redirecting
personal information to another server.

## Session and error behavior

Sessions are stored as owner-only, atomically replaced JSON under
`$XDG_CONFIG_HOME/snlib-cli` (fallback `~/.config/snlib-cli`).
Cookies are bound to `SNLIB_BASE_URL`; changing the origin requires new login.
All account queries and request commands detect rejected sessions, invalidate
them, and persist refreshed cookies after completed operations without extending
the original three-hour TTL. Future login timestamps are rejected.
Legacy Clojure EDN sessions are left untouched and cannot authenticate the native
CLI; log in with the native command once.

- Missing/expired session: `session-missing` / `session-expired`.
- Wrong server: `session-origin-mismatch`.
- Remote authentication rejection: `login-rejected` / `session-rejected`.
- Invalid input: `invalid-command-input` (or JSON parsing codes).
- Network/TLS/size-limit failure: `http-request-failed`.
- HTTP/parse failure: `<command>-request-failed` / `<command>-response-parse-failed`.
- Failed confirmation: `<command>-submit-failed`.
- Persistence failure: `session-save-failed`.

Network timeout, TLS verification, redirect limits, body size limits, and
cookie handling come from the libcurl adapter. No live account was used during
implementation; fixtures and local HTTP tests cannot establish compatibility
with future website changes.

## Offline validation

```bash
moon -C moonbit check --target native
moon -C moonbit test --target native
moon -C moonbit build --target native
python3 scripts/test-moonbit-http.py
python3 scripts/test-moonbit-commands.py
python3 scripts/test-moonbit-regressions.py
python3 scripts/test-skill-launcher.py
```
