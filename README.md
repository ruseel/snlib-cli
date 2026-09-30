# snlib-cli

CLI for Seongnam Library (`snlib.go.kr`). 

## Repository layout

- `clj/` contains the existing, functional Clojure implementation and build.
- `moonbit/` contains the native-target MoonBit implementation. It provides the
  pure typed model, JSON CLI shell, local session preflight, fixture-backed HTML
  extraction, and the transport-independent `search-books` remote/application
  slice. A native HTTP transport is not wired yet.
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

Every invocation emits one typed JSON result envelope. Search responses use
structured JSON data when a transport is supplied; the native executable still
returns `not-implemented` because its live HTTP transport is deferred. Account
commands return `requires-login` until a remembered active session is supplied.

The pure `snlib/model` package imports nothing. Adapters depend inward on the
model/remote boundaries, application composes those ports, the `snlib` facade
depends on application and model, and `snlib/cli` renders typed results as JSON.
`cmd/snlib-cli` only wires process IO to that shell. Dependency versions are
pinned in `moonbit/moon.mod`.

- Account/session (계정/세션): `login`, `my-info` (내 정보 조회)
- Discovery (도서 탐색): `search-books`, `basket` (관심 도서함)
- Status checks (현황 조회): `loan-status` (대출 현황), `interloan-status` (상호대차 현황), `hope-book-list`/`hope-book-detail` (희망도서 신청 내역/상세)
- Write: `interloan-request` (상호대차 신청), `hope-book-request` (희망도서 신청, `--request-edn` 단일 EDN 맵 사용)

The ClawHub skill bundle lives under `skills/snlib-cli` and is deployed to
https://clawhub.ai/ruseel/snlib-cli.
  
See also: `skills/snlib-cli/SKILL.md`
