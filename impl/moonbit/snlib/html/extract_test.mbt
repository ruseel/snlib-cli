///|
fn fixture(path : String) -> String raise {
  @fs.read_file_to_string("../../test/fixtures/snlib/\{path}")
}

///|
fn contract(path : String) -> String raise {
  @fs.read_file_to_string("../../test/fixtures/snlib/contracts/\{path}")
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
test "catalogue reads real registration prefixes from known callbacks" {
  let result = @html.extract_catalogue(
    (
      #|<p class="rtitle"><strong class="themeFC">3건</strong></p>
      #|<ul class="resultList imageType">
      #|<li><dt class="tit"><a>Available</a></dt><dd class="author"></dd>
      #|<a onclick="javascript:fnBandLillApplyPop('MA','CEM000020084'); return false;">Request</a></li>
      #|<li><dt class="tit"><a>On loan</a></dt><dd class="author"></dd>
      #|<a onclick="javascript:fnLoanReservationApplyProc('BEM','BEM000250774','MB','BO'); return false;">Reserve</a></li>
      #|<li><dt class="tit"><a>No action</a></dt><dd class="author"></dd>
      #|<a onclick="unrelated('MG','FIC000000999')">Other</a></li>
      #|</ul>
    ),
  )
  assert_eq(result.items.length(), 3)
  assert_eq(result.items[0].manage_code, "MA")
  assert_eq(result.items[0].registration_number, "CEM000020084")
  assert_eq(result.items[1].manage_code, "MB")
  assert_eq(result.items[1].registration_number, "BEM000250774")
  assert_eq(result.items[2].manage_code, "")
  assert_eq(result.items[2].registration_number, "")
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
test "account information preserves the Clojure labels" {
  let info = @html.extract_my_info(fixture("my-info/page.html"))
  assert_eq(info.user_id, "fixture-reader")
  assert_eq(info.member_no, Some("FICTIONAL-000001"))
  assert_eq(info.member_type, Some("정회원"))
  assert_eq(info.join_date, Some("2026.01.01"))
  assert_eq(info.privacy_expiry_date, Some("2028.01.01"))
  assert_eq(info.phone, Some("010-0000-0000"))
  assert_eq(info.email, Some("reader@example.invalid"))
}

///|
test "account extraction preserves colons and supports missing optional fields" {
  let info = @html.extract_my_info(
    "<div class='myInfo'><ul class='dot-list'><li>아이디 : reader</li><li>이메일주소 : contact:reader@example.invalid</li></ul></div>",
  )
  assert_eq(info.email, Some("contact:reader@example.invalid"))
  assert_eq(info.member_no, None)
}

///|
test "server login redirect and authentication markers are recognized" {
  assert_true(
    @html.logged_out(
      "<form id='redirectForm' action='/intro/menu/10068/program/30025/memberLogin.do'></form>",
    ),
  )
  assert_true(
    @html.logged_out(
      "<script>var frm = document.redirectForm; frm.submit();</script>",
    ),
  )
  assert_true(
    @html.logged_out("<script>location.href='/intro/memberLogin.do';</script>"),
  )
  assert_true(@html.logged_out("<p>로그인 후 이용가능합니다</p>"))
  assert_true(
    @html.authenticated_page(fixture("loan-status/page-article-list.html")),
  )
  assert_false(@html.authenticated_page("<html>HTTP 200</html>"))
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
