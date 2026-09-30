///|
fn fixture(path : String) -> String raise {
  @fs.read_file_to_string("../../fixtures/snlib/\{path}")
}

///|
fn response(body : String) -> @remote.HttpResponse {
  { status: 200, body }
}

///|
test "fixture transport preserves Clojure catalogue contract" {
  let html = fixture("search-books/interloan-target.html")
  let result = @application.search_books(
    @model.search_books_input("삼국지 1", per_page=20),
    _ => response(html),
  )
  guard result.data is Some(SearchBooksResult(data)) else {
    fail("expected structured search data")
  }
  assert_eq(result.outcome, Ok)
  assert_eq(data.page, 1)
  assert_eq(data.total_count, 12)
  assert_eq(data.items.length(), 10)
  assert_eq(data.items[0].title, "삼국지 1")
  assert_eq(data.items[0].author, "나관중 지음")
  assert_eq(data.items[0].publisher, "가상출판사")
  assert_eq(data.items[0].publication_year, "2024")
  assert_eq(data.items[0].manage_code, "MG")
  assert_eq(data.items[0].registration_number, "FIC000000101")
  assert_eq(data.items[9].manage_code, "MB")
  assert_eq(data.items[9].registration_number, "FIC000000110")
}

///|
test "valid empty catalogue is successful" {
  let result = @application.search_books(
    @model.search_books_input("missing", page=4),
    _ => {
      response(
        "<html><p class='rtitle'><strong class='themeFC'>0건</strong></p><ul class='resultList imageType'></ul></html>",
      )
    },
  )
  guard result.data is Some(SearchBooksResult(data)) else {
    fail("expected structured empty search data")
  }
  assert_eq(result.outcome, Ok)
  assert_eq(data.page, 4)
  assert_eq(data.total_count, 0)
  assert_eq(data.items, [])
}

///|
test "application exposes stable remote error codes" {
  let input = @model.search_books_input("moonbit")
  let network = @application.search_books(input, _ => {
    raise @remote.Failed("offline")
  })
  assert_eq(network.outcome, RemoteError)
  assert_eq(network.code, "http-request-failed")
  let http = @application.search_books(input, _ => {
    ({ status: 429, body: "slow down" } : @remote.HttpResponse)
  })
  assert_eq(http.outcome, RemoteError)
  assert_eq(http.code, "search-request-failed")
  let malformed = @application.search_books(input, _ => {
    response("<html>no catalogue facts</html>")
  })
  assert_eq(malformed.outcome, RemoteError)
  assert_eq(malformed.code, "search-response-parse-failed")
}
