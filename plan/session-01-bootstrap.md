# SNLib CLI Library - Session 01 Plan

## Goal

Build an initial Clojure library (for use inside a CLI) with a safe development baseline:

- Clojure `1.12`
- top-level directories: `src/`, `test/`, `data/fixtures/`
- mock-based tests
- each top-level function returns a map extracted from HTML
- development/test safety guard: block real completion submit for
  - `상호대차신청`
  - `희망도서신청`

## Initial Feature Surface

1. `login` (`로그인`)
2. `search` (`검색`)
3. `interlibrary-loan-request` (`상호대차신청`)
4. `loan-status` (`대출현황`)
5. `wish-book-request` (`희망도서신청`)

## Delivery Plan

1. Project bootstrap
   - initialize git repository
   - create `deps.edn` with Clojure `1.12.0`
   - create directory skeleton and baseline namespaces

2. HTML-to-map extraction baseline
   - add dedicated parsing namespace (`snlib-cli.html`)
   - define one extraction function per feature
   - keep returned shape deterministic (maps + vectors)

3. Safety constraints for risky workflows
   - enforce non-submitting mode for `interlibrary-loan-request`
   - enforce non-submitting mode for `wish-book-request`
   - return explicit blocked metadata (`:submission-allowed? false`, `:blocked-reason ...`)

4. Test strategy (mock only)
   - keep HTML fixtures in `data/fixtures/`
   - write unit tests that parse fixture HTML and assert map shape/value
   - avoid any real network or submit side effects in tests

5. Backpressure setup (skill-aligned)
   - add `tests.edn` and `:test` alias (Kaocha)
   - add `.pre-commit-config.yaml` with:
     - `standard-clj fix src test`
     - `clj -M:test --fail-fast`
   - install hooks with `prek install`

## Next Session

1. Replace regex extraction with robust HTML parser if target markup is complex.
2. Add request adapters (HTTP layer) separated from parsing layer.
3. Define error maps for invalid login/session-expired/rate-limit cases.
