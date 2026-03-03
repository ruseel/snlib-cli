# snlib-cli command patterns

## Login

```bash
./scripts/snlib-cli login --pretty
```

## Search

```bash
./scripts/snlib-cli search-books --keyword "키워드" --pretty
./scripts/snlib-cli search-books --keyword "키워드" --manage-code MA --page 1 --per-page 10 --pretty
```

## My page (read-only)

```bash
./scripts/snlib-cli my-info --pretty
./scripts/snlib-cli loan-status --pretty
./scripts/snlib-cli loan-history --pretty
./scripts/snlib-cli reservation-status --pretty
./scripts/snlib-cli interloan-status --pretty
./scripts/snlib-cli hope-book-list --pretty
./scripts/snlib-cli hope-book-detail --rec-key 1938103961 --pretty
./scripts/snlib-cli basket-list --pretty
./scripts/snlib-cli basket-list --group-key 13840 --pretty
```

## Write operations (explicit submit)

### Interloan request

```bash
./scripts/snlib-cli interloan-request \
  --manage-code MA \
  --reg-no CEM000334796 \
  --apl-lib-code 141484 \
  --submit --allow-submit --pretty
```

### Hope-book request

```bash
./scripts/snlib-cli hope-book-request \
  --request-edn '{:title "도서명" :author "저자" :publisher "출판사" :publishYear "2025" :eaIsbn "9788966264896" :price "54000" :email "user@example.com" :smsReceiptYn "Y" :handPhone "010-1234-5678"}' \
  --submit --allow-submit --pretty
```

## GitHub ref override (tag only)

```bash
SNLIB_GIT_TAG=v0.2.0 ./scripts/snlib-cli my-info --pretty
SNLIB_GITHUB_REPO=https://github.com/example/snlib-cli SNLIB_GIT_TAG=v0.2.0 ./scripts/snlib-cli loan-status --pretty
```
