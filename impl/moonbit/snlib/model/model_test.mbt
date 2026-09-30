///|
test "manage codes normalize and validate ASCII letters" {
  inspect(@model.manage_code(" ma "), content="MA")
  assert_true(@model.valid_manage_code("MA"))
  assert_false(@model.valid_manage_code("M1"))
  assert_false(@model.valid_manage_code("가나"))
}

///|
test "library codes are syntactic six-digit values" {
  inspect(@model.library_code(" 141484 "), content="141484")
  assert_true(@model.valid_library_code("999999"))
  assert_false(@model.valid_library_code("14148"))
  assert_false(@model.valid_library_code("14148A"))
}

///|
test "sessions reject future timestamps and expire at exactly three hours" {
  let session : @model.Session = {
    patron_id: "reader",
    authenticated_at_ms: 1000UL,
    remembered: true,
    status: Active,
  }
  assert_false(session.is_usable(999UL))
  assert_true(session.is_usable(1000UL))
  assert_true(session.is_usable(1000UL + @model.session_duration_ms - 1UL))
  assert_false(session.is_usable(1000UL + @model.session_duration_ms))
}

///|
test "command defaults remain stable" {
  let search = @model.search_books_input("moonbit")
  assert_eq(search.page, 1)
  assert_eq(search.per_page, 10)
  assert_eq(search.sort, "SIMILAR")
  assert_eq(search.order, "DESC")
  assert_false(@model.loan_status_input().include_history)
  let request = @model.interloan_request_input("MA", "R1", "141484")
  assert_eq(request.appendix_apply_yn, "N")
}
