# snlib-cli

CLI for Seongnam Library (`snlib.go.kr`). 

## Repository layout

- `clj/` contains the existing, functional Clojure implementation and build.
- `moonbit/` contains the native-target MoonBit implementation. Stage 1 provides
  the pure typed model, JSON CLI shell, and local session preflight; remote
  command execution is intentionally not implemented yet.
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

With the MoonBit CLI installed, validate Stage 1 using `moon -C moonbit check`
and `moon -C moonbit test snlib/model && moon -C moonbit test snlib/cli`.
The first positional argument selects a command and stdin supplies exactly one
JSON object. For example:

```bash
printf '%s\n' '{"keyword":"moonbit"}' |
  moon -C moonbit run cmd/snlib-cli -- search-books
```

Every invocation emits one typed JSON result envelope. Valid command input
currently returns `not-implemented`; account commands return `requires-login`
until a remembered active session is supplied by a later persistence stage.

The pure `snlib/model` package imports nothing. Adapters depend inward on the
model/remote boundaries, application composes those ports, the `snlib` facade
depends on application and model, and the new `snlib/cli` package depends only
on the model and core JSON. `cmd/snlib-cli` only wires process IO to that shell.
No third-party MoonBit dependency is needed at Stage 1.

- Account/session (계정/세션): `login`, `my-info` (내 정보 조회)
- Discovery (도서 탐색): `search-books`, `basket` (관심 도서함)
- Status checks (현황 조회): `loan-status` (대출 현황), `interloan-status` (상호대차 현황), `hope-book-list`/`hope-book-detail` (희망도서 신청 내역/상세)
- Write: `interloan-request` (상호대차 신청), `hope-book-request` (희망도서 신청, `--request-edn` 단일 EDN 맵 사용)

The ClawHub skill bundle lives under `skills/snlib-cli` and is deployed to
https://clawhub.ai/ruseel/snlib-cli.
  
See also: `skills/snlib-cli/SKILL.md`
