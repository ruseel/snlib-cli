///|
fn fixture(path : String) -> String raise {
  @fs.read_file_to_string("../fixtures/snlib/\{path}")
}

///|
fn contract(path : String) -> String raise {
  @fs.read_file_to_string("../fixtures/snlib/contracts/\{path}")
}

///|
test "catalogue preserves fixture order and external total" {
  let manifest = contract("search-interloan.json")
  assert_true(manifest.contains("\"total_count\": 12"))
  let result = @html.extract_catalogue(
    fixture("search-books/interloan-target.html"),
  )
  assert_eq(result.total_count, 12)
  assert_eq(result.items.length(), 10)
  assert_eq(result.items[0].registration_number, "FIC000000101")
  assert_eq(result.items[0].manage_code, "MG")
  assert_eq(result.items[9].registration_number, "FIC000000110")
  assert_eq(result.items[9].manage_code, "MB")
}

///|
test "catalogue extracts hope target identity" {
  let manifest = contract("search-hope.json")
  assert_true(manifest.contains("삼국지 2, 적벽대전"))
  let result = @html.extract_catalogue(
    fixture("search-books/hope-book-target.html"),
  )
  assert_eq(result.total_count, 1)
  assert_eq(result.items.length(), 1)
  assert_eq(result.items[0].title, "삼국지 2, 적벽대전")
  assert_eq(result.items[0].manage_code, "MP")
  assert_eq(result.items[0].registration_number, "FIC000000202")
}

///|
test "current loans retain source date and status text" {
  assert_true(contract("loan-status.json").contains("\"loan_count\": 6"))
  let loans = @html.extract_current_loans(
    fixture("loan-status/page-article-list.html"),
  )
  assert_eq(loans.length(), 6)
  assert_eq(loans[0].loan_date_text, "2026.04.01")
  assert_eq(loans[0].due_date_text, "2026.04.15")
  assert_eq(
    loans[2].return_status_text,
    "대출중 (제3자 예약중이므로 연기불가)",
  )
}

///|
test "hope defaults expose aliases and normalized phone" {
  assert_true(
    contract("hope-preparation-success.json").contains("010-1234-5678"),
  )
  let defaults = @html.extract_hope_defaults(
    fixture("hope-book-request/page.html"),
  )
  assert_eq(defaults.email, "masked@example.com")
  assert_eq(defaults.phone, "010-1234-5678")
  assert_eq(defaults.fields["mobile_no_1"], "010")
  assert_eq(defaults.fields["handPhone"], "010-1234-5678")
}

///|
test "interloan preparation extracts derived form values" {
  assert_true(
    contract("interloan-preparation-failure.json").contains("9999999999"),
  )
  let prepared = @html.extract_interloan_preparation(
    fixture("interloan-request/popup-samgukji-1.html"),
  )
  assert_eq(prepared.manage_code, "MG")
  assert_eq(prepared.registration_number, "FIC000000101")
  assert_eq(prepared.providing_library_code, "141142")
  assert_eq(prepared.appendix_apply, "N")
  assert_eq(prepared.user_key, "9999999999")
}

///|
test "logged-out detection distinguishes authenticated fixture" {
  assert_false(@html.logged_out(fixture("loan-status/page-article-list.html")))
  assert_true(@html.logged_out("<html><a href='/login'>로그인</a></html>"))
}

///|
test "submission verdict requires explicit domain evidence" {
  assert_true(
    @html.classify_submission(
      fixture("hope-book-request/submit-samgukji-2.html"),
    )
    is @model.Submitted(_),
  )
  assert_true(
    @html.classify_submission(
      fixture("interloan-request/submit-samgukji-1.html"),
    )
    is @model.SubmitFailed(_),
  )
  assert_true(
    @html.classify_submission("<html><body>HTTP 200</body></html>")
    is @model.SubmitFailed("unknown response"),
  )
}
