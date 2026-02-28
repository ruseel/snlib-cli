# Phase 5: 마이페이지 조회 기능 확장

## 개요

HAR `snlib.go.kr-ext.har`에서 발견된 신규 엔드포인트 6종을 `snlib.core`에 추가한다.
모두 **읽기 전용(GET)** 기능이며 (관심도서 목록 조회만 POST), 기존 패턴(로그인 확인 → HTML 파싱 → 공통 envelope 반환)을 따른다.

## 신규 기능 목록

| # | 함수명 | 엔드포인트 | Method |
|---|--------|-----------|--------|
| 1 | `my-info!` | `/intro/menu/10055/program/30017/mypage/myInfo.do` | GET |
| 2 | `reservation-status!` | `/intro/menu/10061/program/30020/mypage/reservationStatusList.do` | GET |
| 3 | `loan-history!` | `/intro/menu/10062/program/30021/mypage/loanHistoryList.do` | GET |
| 4 | `interloan-status!` | `/intro/bandLillStatusList.do` | GET |
| 5 | `hope-book-list!` | `/intro/menu/10065/program/30011/mypage/hopeBookList.do` | GET |
|   | `hope-book-detail!` | `/intro/menu/10065/program/30011/mypage/hopeBookDetail.do` | GET |
| 6 | `basket-list!` | `basketGroupMain.do` → `basketGroupBookList.do` | GET+POST |

## 공통 패턴

모든 함수는 기존 `loan-status!`와 동일한 구조:

```clojure
(defn feature!
  [client opts]
  (try
    (let [res (request client {:method :get :url path :headers ...})
          html (:body res)]
      (cond
        (logged-out-page? html) → {:ok? false :status :requires-login ...}
        (= 200 status)         → {:ok? true  :status :ok :data (parse-xxx html) ...}
        :else                  → error-envelope))
    (catch Exception e → error-envelope)))
```

---

## 기능별 상세

### 5.1 `my-info!` — 내 정보 조회

- **요청**: `GET /intro/menu/10055/program/30017/mypage/myInfo.do` (파라미터 없음)
- **`:data` shape**:
  ```clojure
  {:name string?        ;; 이름
   :user-id string?     ;; 아이디
   :phone string?       ;; 전화번호
   :email string?       ;; 이메일
   :address string?     ;; 주소
   :library string?}    ;; 이용도서관
  ```
- **파싱 전략**: 마이페이지 개인정보 테이블/dl 구조에서 label-value 쌍 추출
- **참고**: 실제 HTML 구조를 로그인 후 확인하여 셀렉터 조정 필요

### 5.2 `reservation-status!` — 예약 현황

- **요청**: `GET /intro/menu/10061/program/30020/mypage/reservationStatusList.do` (파라미터 없음)
- **`:data` shape**:
  ```clojure
  {:reservations [{:title string?
                    :reservation-date string?
                    :status string?
                    :rank string?           ;; 예약순위
                    :pickup-library string?  ;; 수령도서관
                    :cancellable? boolean?}]
   :count int?}
  ```
- **파싱 전략**: `loan-status!`와 동일한 테이블 파싱. 헤더 키워드만 다름:
  - 서명/도서명 → `:title`
  - 예약일 → `:reservation-date`
  - 예약상태 → `:status`
  - 예약순위/순위 → `:rank`
  - 수령관 → `:pickup-library`

### 5.3 `loan-history!` — 대출 이력

- **요청**: `GET /intro/menu/10062/program/30021/mypage/loanHistoryList.do` (파라미터 없음)
- **`:data` shape**:
  ```clojure
  {:loans [{:title string?
            :loan-date string?
            :return-date string?
            :due-date string?
            :library string?}]
   :count int?}
  ```
- **파싱 전략**: 기존 `parse-loans-from-table`과 유사하나, "반납일" 컬럼 추가 필요. 
  기존 `loan-header-key`를 확장하거나 별도 header-key 함수 작성.
- **참고**: `loan-status!`의 `:include-history? true`와 겹칠 수 있으나, 이 페이지는 전체 이력 전용 뷰로 
  더 많은 과거 데이터를 보여줄 가능성이 있음.

### 5.4 `interloan-status!` — 상호대차 신청 현황

- **요청**: `GET /intro/bandLillStatusList.do` (파라미터 없음)
- **`:data` shape**:
  ```clojure
  {:requests [{:title string?
               :apply-date string?      ;; 신청일
               :status string?          ;; 처리상태 (신청/승인/대출중/반납 등)
               :give-library string?    ;; 소장도서관
               :apl-library string?     ;; 수령도서관
               :due-date string?}]      ;; 반납예정일
   :count int?}
  ```
- **파싱 전략**: 테이블 기반. 상호대차 관련 헤더 키워드 매핑 필요.

### 5.5 `hope-book-list!` / `hope-book-detail!` — 희망도서 신청 목록 / 상세

#### `hope-book-list!`
- **요청**: `GET /intro/menu/10065/program/30011/mypage/hopeBookList.do` (파라미터 없음)
- **`:data` shape**:
  ```clojure
  {:items [{:title string?
            :author string?
            :publisher string?
            :apply-date string?
            :status string?          ;; 처리상태 (접수/심사중/구입결정/정리중 등)
            :rec-key string?}]       ;; 상세 조회용 키
   :count int?}
  ```

#### `hope-book-detail!`
- **요청**: `GET /intro/menu/10065/program/30011/mypage/hopeBookDetail.do`
  - **query-params**: `recKey` (필수), `currentPageNo`, `searchLibrary`, `searchStatus`, `searchKey`, `searchValue`, `manageCode`, `furnishStatus`
- **`:data` shape**:
  ```clojure
  {:title string?
   :author string?
   :publisher string?
   :publish-year string?
   :isbn string?
   :apply-date string?
   :status string?
   :furnish-status string?   ;; 비치상태
   :library string?
   :opinion string?}         ;; 신청사유
  ```
- **파싱 전략**: 상세 페이지는 테이블 또는 dl/dd 구조. label-value 매핑.

### 5.6 `basket-list!` — 관심도서 바구니

- **요청**: 2단계
  1. `GET /intro/menu/10057/program/30018/mypage/basketGroupMain.do` → 바구니 그룹 목록 + 첫 번째 그룹의 `searchGroupKey` 획득
  2. `POST /intro/menu/10057/program/30018/mypage/basketGroupBookList.do`
     - **form-params**: `searchGroupKey`, `searchLibrary` (default `"ALL"`), `searchKey` (default `"TITLE"`), `searchValue`
- **`:data` shape**:
  ```clojure
  {:groups [{:group-key string?
             :group-name string?
             :book-count int?}]
   :books [{:title string?
            :author string?
            :publisher string?
            :publish-year string?}]
   :count int?}
  ```
- **옵션**: `{:group-key string?}` — 특정 그룹 지정. 미지정 시 첫 번째 그룹 사용.

---

## 구현 순서

단순한 것부터 → 복잡한 것 순서로:

1. **`my-info!`** — 단일 GET, 가장 단순
2. **`loan-history!`** — 기존 loan 파싱 재활용
3. **`reservation-status!`** — 테이블 파싱, 컬럼만 다름
4. **`interloan-status!`** — 테이블 파싱
5. **`hope-book-list!`** + **`hope-book-detail!`** — 목록+상세 세트
6. **`basket-list!`** — 2단계 요청(GET+POST)으로 가장 복잡

## 구현 방식

### core.clj 추가 사항
- path 상수 6개 추가
- private 파서 함수 6개 (`parse-my-info`, `parse-reservation-rows`, `parse-loan-history-rows`, `parse-interloan-status-rows`, `parse-hope-book-list-items`, `parse-hope-book-detail`, `parse-basket-groups`, `parse-basket-books`)
- public 함수 7개

### cli.clj 추가 사항
- `commands` 맵에 7개 추가: `"my-info"`, `"reservation-status"`, `"loan-history"`, `"interloan-status"`, `"hope-book-list"`, `"hope-book-detail"`, `"basket-list"`
- `command-opts`에 case 분기 추가
- CLI 옵션: `--rec-key`, `--group-key` 추가
- usage 텍스트 업데이트

### 테스트
- 각 함수별 최소 2개 테스트:
  1. 정상 응답 파싱 (stub HTML)
  2. 로그인 필요 감지
- HTML stub은 실제 로그인 후 페이지 구조를 확인하여 작성

## 주의사항

- 실제 HTML 구조는 로그인 세션으로 직접 확인해야 정확한 CSS 셀렉터를 잡을 수 있음
- 첫 구현은 **뼈대(skeleton)** 우선: 요청 → 로그인 확인 → 빈 파서 → 테스트
- 이후 실제 HTML을 보며 파서를 하나씩 완성
