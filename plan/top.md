# snlib CLI Library Plan

## 1. Goal

Build an initial Clojure library for a CLI targeting 성남도서관 (`snlib`) with a safe baseline:

- Clojure `1.12`
- top-level layout: `src/`, `test/`, `data/fixtures/`
- mock/fixture-first tests
- each public feature function returns a map (parsed from HTML/HTTP response)
- safety guard in dev/test: block real final submit for:
  - `상호대차신청`
  - `희망도서신청`

## 2. Reference Input Data

HAR analysis source:

- `H=/Users/ruseel/p/tries/2026-02-26-snlib-har-extracting-pilot`

Use `H` to confirm endpoint path, method, params, and response pattern for every feature.

## 3. Public API Draft (Feature Signatures)

Namespace: `snlib.core`

Common return envelope for all feature functions:

```clojure
{:ok? boolean
 :status keyword
 :data map?
 :error {:code keyword :message string}?}
```

### 3.1 login

```clojure
(defn login!
  [client {:keys [user-id password return-url]}])
```

Expected `:data` shape (minimum):

```clojure
{:authenticated? boolean
 :user-id string?}
```

### 3.2 search

```clojure
(defn search-books!
  [client {:keys [keyword library-code page per-page sort order]}])
```

Expected `:data` shape (minimum):

```clojure
{:items [{:title string?
          :author string?
          :publisher string?
          :publish-year string?
          :manage-code string?
          :reg-no string?}]
 :page int?
 :total-count int?}
```

### 3.3 interlibrary-loan-request

```clojure
(defn interlibrary-loan-request!
  [client {:keys [manage-code reg-no give-lib-code apl-lib-code user-key appendix-apply-yn submit?]}])
```

Rule:

- when `submit?` is true, default policy must block in dev/test unless explicitly allowed.

Expected `:data` shape (minimum):

```clojure
{:prepared-payload map?
 :submit-attempted? boolean
 :submit-blocked? boolean
 :result-message string?}
```

### 3.4 loan-status

```clojure
(defn loan-status!
  [client {:keys [include-history?]}])
```

Expected `:data` shape (minimum):

```clojure
{:loans [{:title string?
          :loan-date string?
          :due-date string?
          :return-status string?
          :renewable? boolean?}]
 :count int?}
```

### 3.5 hope-book-request

```clojure
(defn hope-book-request!
  [client {:keys [book-info applicant-info submit?]}])
```

Rule:

- when `submit?` is true, default policy must block in dev/test unless explicitly allowed.

Expected `:data` shape (minimum):

```clojure
{:prepared-payload map?
 :submit-attempted? boolean
 :submit-blocked? boolean
 :result-message string?}
```

## 4. Implementation Phases

### Phase 0. Fixture/HAR baseline

- extract one valid request/response fixture per feature from `H`
- store sanitized fixtures under `data/fixtures/`
- record endpoint contract in docs (method/path/required params)

### Phase 1. Core client skeleton

- create `snlib.core` with `create-client` + session/cookie handling
- add shared request helper + HTML parser entrypoints
- add write-policy gate (deny by default)

### Phase 2. Feature implementation

- implement `login!`
- implement `search-books!`
- implement `loan-status!`
- implement `interlibrary-loan-request!` as safe-by-default (payload build + blocked submit)
- implement `hope-book-request!` as safe-by-default (payload build + blocked submit)

### Phase 3. Tests

- unit tests for parser and payload builders
- contract tests vs fixture data for each feature
- live tests only for read-only paths:
  - `login!`
  - `search-books!`
  - `loan-status!`
- write features (`interlibrary-loan-request!`, `hope-book-request!`) tested only for block behavior in dev/test

### Phase 4. CLI integration

- map CLI commands/options to `snlib.core` signatures
- validate input and normalize error output envelope

## 5. Done Criteria

- all five feature functions exist with the signatures above
- each function returns the common envelope map
- fixture-based tests pass
- read-only live tests pass (if credentials/env provided)
- write submit calls remain blocked by default in dev/test
