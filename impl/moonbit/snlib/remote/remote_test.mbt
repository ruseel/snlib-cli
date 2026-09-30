///|
fn value(request : @remote.HttpRequest, key : String) -> Array[String] {
  request.query.get(key).unwrap_or([])
}

///|
test "search request matches Clojure endpoint defaults and headers" {
  let request = @remote.build_search_request(
    @model.search_books_input("삼국지"),
  )
  assert_eq(request.verb, Get)
  assert_eq(request.base_url, "https://snlib.go.kr")
  assert_eq(
    request.path,
    "/intro/menu/10041/program/30009/plusSearchResultList.do",
  )
  assert_eq(request.headers["User-Agent"], "Mozilla/5.0 snlib-lib")
  assert_eq(
    request.headers["Referer"],
    "https://snlib.go.kr/intro/main/index.do",
  )
  assert_eq(value(request, "searchKeyword"), ["삼국지"])
  assert_eq(value(request, "preSearchKeyword"), ["삼국지"])
  assert_eq(value(request, "currentPageNo"), ["1"])
  assert_eq(value(request, "searchRecordCount"), ["10"])
  assert_eq(value(request, "searchSort"), ["SIMILAR"])
  assert_eq(value(request, "searchOrder"), ["DESC"])
  assert_eq(value(request, "searchPbLibrary"), ["ALL"])
  assert_eq(value(request, "searchSmLibrary"), [""])
  assert_eq(value(request, "searchLibraryArr"), [])
}

///|
test "search request preserves explicit paging and repeated library values" {
  let request = @remote.build_search_request(
    @model.search_books_input(
      "moonbit",
      manage_codes=["MA", "MB"],
      page=3,
      per_page=20,
      sort="TITLE",
      order="ASC",
    ),
  )
  assert_eq(value(request, "currentPageNo"), ["3"])
  assert_eq(value(request, "searchRecordCount"), ["20"])
  assert_eq(value(request, "searchSort"), ["TITLE"])
  assert_eq(value(request, "searchOrder"), ["ASC"])
  assert_eq(value(request, "searchPbLibrary"), [""])
  assert_eq(value(request, "searchLibraryArr"), ["MA", "MB"])
}

///|
test "transport and HTTP failures remain distinct" {
  let input = @model.search_books_input("moonbit")
  let network = @remote.search_books(input, _ => raise @remote.Failed("offline"))
  assert_eq(network, Failure(Network("offline")))
  let non_2xx = @remote.search_books(input, _ => {
    ({ status: 503, body: "unavailable" } : @remote.HttpResponse)
  })
  assert_eq(non_2xx, Failure(HttpStatus(503)))
}

///|
test "malformed search HTML is a parse failure" {
  let result = @remote.search_books(@model.search_books_input("moonbit"), _ => {
    (
      { status: 200, body: "<html><body>broken</body></html>" } :
      @remote.HttpResponse)
  })
  assert_true(result is Failure(Parse(_)))
}
