# snlib-cli

CLI for Seongnam Library (`snlib.go.kr`). 

- Account/session (계정/세션): `login`, `my-info` (내 정보 조회)
- Discovery (도서 탐색): `search-books`, `basket` (관심 도서함)
- Status checks (현황 조회): `loan-status` (대출 현황), `interloan-status` (상호대차 현황), `hope-book-list`/`hope-book-detail` (희망도서 신청 내역/상세)
- Write: `interloan-request` (상호대차 신청), `hope-book-request` (희망도서 신청, `--request-edn` 단일 EDN 맵 사용)

The ClawHub skill bundle lives under `skills/snlib-cli` and
  deployed to https://clawhub.ai/ruseel/snlib-cli
  
See also: `skills/snlib-cli/SKILL.md`
