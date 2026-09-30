# snlib-cli

CLI for Seongnam Library (`snlib.go.kr`). 

## Repository layout

- `clj/` contains the existing, functional Clojure implementation and build.
- `moonbit/` contains the native-target MoonBit implementation. It provides the
  pure typed model, JSON CLI shell, local session preflight, fixture-backed HTML
  extraction, and live execution of all advertised commands through a native
  libcurl HTTP adapter with persistent, origin-bound session cookies. Request
  commands prepare by default and submit only with explicit JSON `submit:true`.
- `fixtures/` contains language-neutral HTML fixtures. The JSON manifests in
  `fixtures/snlib/contracts/` describe each fixture flow's input, ordered
  responses, and observed output facts.
- `skills/snlib-cli/` contains the published ClawHub skill bundle.

Run Clojure commands from the Clojure project directory:

```bash
cd clj
clojure -M:test
clojure -M -m snlib.cli --help
clojure -T:build jar :version '"0.1.0"'
```

The native MoonBit build requires a C compiler and libcurl headers/library.
On macOS, install the Xcode Command Line Tools (`xcode-select --install`); the
system SDK supplies libcurl. On Debian/Ubuntu, install `build-essential` and
`libcurl4-openssl-dev`. Native executables consuming `snlib/http_session` must
link with `-lcurl` (configured in `cmd/snlib-cli/moon.pkg`).

With the MoonBit CLI installed, initialize a fresh registry once with
`moon -C moonbit update`, then validate the pinned dependencies and project
using `moon -C moonbit check`, `moon -C moonbit test`, and
`moon -C moonbit build`.
The first positional argument selects a command and stdin supplies exactly one
JSON object. For example:

```bash
printf '%s\n' '{"keyword":"moonbit"}' |
  moon -C moonbit run cmd/snlib-cli -- search-books
```

Every invocation emits one typed JSON result envelope. `search-books` queries
`https://snlib.go.kr` without login and returns structured JSON with `items`,
`page`, and `total_count`. Optional JSON fields include `manage_codes` (an array
of codes), `page`, `per_page`, `sort`, and `order`.

The HTTP adapter verifies TLS certificates, follows at most five redirects
(without downgrading HTTPS to HTTP), decompresses responses, and limits decoded
bodies to 8 MiB. Connection and total request timeouts are 10 and 30 seconds.
Network/TLS failures, non-2xx HTTP responses, and invalid catalogue HTML retain
separate error codes: `http-request-failed`, `search-request-failed`, and
`search-response-parse-failed`.

Set `SNLIB_BASE_URL` to override the server for local testing. Offline integration
tests build the native CLI and exercise it against a temporary local HTTP server:

```bash
python3 scripts/test-moonbit-http.py
```

These tests require Python 3; OpenSSL enables the untrusted-certificate test.
They do not access the live service or your saved credentials/session.

### MoonBit login and account information

`login` accepts `{"user_id":"YOUR_ID","password":"YOUR_PASSWORD"}` on stdin.
It fetches the login page, submits the form, then independently verifies login
using the loan-status page. An HTTP 200 without an authenticated-page marker
is not considered success. Login POST redirects are not followed, so passwords
are never replayed to a redirect destination. Login requires HTTPS except for
literal loopback test servers (`localhost` / `127.0.0.1`).

For example, in Nushell, read a private JSON file rather than putting your
password in shell history or process arguments:

```nu
open --raw /path/to/private-login.json | ^moon -C moonbit run cmd/snlib-cli -- login
'{}' | ^moon -C moonbit run cmd/snlib-cli -- my-info
```

Keep that input file outside the repository with owner-only permissions (`0600`)
and remove it when no longer needed. The CLI does **not** save the password.
On successful login it atomically writes `session.json` with permissions `0600`
under `$XDG_CONFIG_HOME/snlib-cli` (or `~/.config/snlib-cli`). Session format v2
contains the patron ID, login time, server base URL, and libcurl cookie records.
Cookies are reused across processes, honoring domain, path, expiry and Secure
attributes. They are never included in CLI output or sent by public search.

Sessions expire locally after three hours. `my-info` also detects server-side
expiry/rejection and invalidates the saved cookie session. Refreshed cookies
are persisted without extending the original three-hour limit. Failed remote
logins invalidate the previous session; malformed login input leaves it alone.
Old metadata-only MoonBit sessions and legacy Clojure EDN sessions cannot
supply these cookies: log in once with the native CLI. Changing `SNLIB_BASE_URL`
also requires a new login; cookies from another server are never reused.

`my-info` returns `user_id`, `member_no`, `member_type`, `join_date`,
`privacy_expiry_date`, `phone`, and `email`; absent optional fields are `null`.
The result is personal data, so avoid storing its output in shared logs.
Authentication rejection uses `login-rejected` / `session-rejected`;
missing/expired sessions use `session-missing` / `session-expired`;
an origin mismatch uses `session-origin-mismatch`. HTTP, parse, and persistence
failures use distinct remote-error codes, including `session-save-failed`.

All account queries and request commands are implemented. Request commands
prepare by default; set JSON `submit:true` only after confirming the payload.
HTTP 200 alone is not considered submission success. Never automatically retry
writes after network or persistence failures; check request status first.
See [the native command guide](docs/moonbit-commands.md) for schemas and
[implementation progress](docs/moonbit-todos.md) for the completed checklist.
No live account credentials are needed for the offline test suite; it uses
fictional data and local HTTP servers. Run the additional command contracts with
`python3 scripts/test-moonbit-commands.py`, regression checks with
`python3 scripts/test-moonbit-regressions.py`, and launcher checks with
`python3 scripts/test-skill-launcher.py`.

The pure `snlib/model` package imports nothing. Adapters depend inward on the
model/remote boundaries, application composes those ports, the `snlib` facade
depends on application and model, and `snlib/cli` renders typed results as JSON.
`cmd/snlib-cli` wires process IO and the native HTTP adapter to that shell. Dependency versions are
pinned in `moonbit/moon.mod`.

- Account/session (계정/세션): `login`, `my-info` (내 정보 조회)
- Discovery (도서 탐색): `search-books`, `basket` (관심 도서함)
- Status checks (현황 조회): `loan-status` (대출 현황), `interloan-status` (상호대차 현황), `hope-book-list`/`hope-book-detail` (희망도서 신청 내역/상세)
- Write: `interloan-request` (상호대차 신청), `hope-book-request` (희망도서 신청). Native commands use JSON stdin with explicit `submit:true`; the Clojure CLI uses flags and `--request-edn`.

The ClawHub skill bundle lives under `skills/snlib-cli` and is deployed to
https://clawhub.ai/ruseel/snlib-cli.
  
See also: `skills/snlib-cli/SKILL.md`
