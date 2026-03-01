# snlib-cli command patterns

## Login

```bash
./scripts/snlib login --pretty
```

## Search

```bash
./scripts/snlib search-books --keyword "키워드" --pretty
./scripts/snlib search-books --keyword "키워드" --library-code MA --page 1 --per-page 10 --pretty
```

## My page (read-only)

```bash
./scripts/snlib my-info --pretty
./scripts/snlib loan-status --pretty
./scripts/snlib loan-history --pretty
./scripts/snlib reservation-status --pretty
./scripts/snlib interloan-status --pretty
./scripts/snlib hope-book-list --pretty
./scripts/snlib hope-book-detail --rec-key 1938103961 --pretty
./scripts/snlib basket-list --pretty
./scripts/snlib basket-list --group-key 13840 --pretty
```

## Write operations (explicit submit)

### Interloan request

```bash
./scripts/snlib interloan-request \
  --manage-code MA \
  --reg-no CEM000334796 \
  --apl-lib-code 141484 \
  --submit --allow-submit --pretty
```

### Hope-book request

```bash
./scripts/snlib hope-book-request \
  --book-info title="도서명" \
  --book-info author="저자" \
  --applicant-info remark="신청 사유" \
  --submit --allow-submit --pretty
```

## Artifact override

```bash
SNLIB_VERSION=0.2.0 ./scripts/snlib my-info --pretty
SNLIB_GROUP=com.example SNLIB_ARTIFACT=snlib-cli ./scripts/snlib loan-status --pretty
```
