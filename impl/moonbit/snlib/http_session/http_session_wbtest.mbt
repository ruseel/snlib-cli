///|
test "query encoding uses UTF-8 and preserves repeated and empty values" {
  let request : @remote.HttpRequest = {
    verb: Get,
    base_url: "https://example.test",
    path: "/search",
    query: {
      "keyword": ["삼국지 & +/?#"],
      "library": ["MG", "MB"],
      "empty": [""],
    },
    headers: {},
  }
  assert_eq(
    request_url(request, request.base_url),
    "https://example.test/search?empty=&keyword=%EC%82%BC%EA%B5%AD%EC%A7%80%20%26%20%2B%2F%3F%23&library=MG&library=MB",
  )
  assert_eq(percent_encode("AZaz09-._~"), "AZaz09-._~")
}

///|
test "empty query has no question mark" {
  let request : @remote.HttpRequest = {
    verb: Get,
    base_url: "https://example.test",
    path: "/search",
    query: {},
    headers: {},
  }
  assert_eq(
    request_url(request, request.base_url),
    "https://example.test/search",
  )
}

///|
test "credentials require HTTPS except literal loopback test servers" {
  assert_true(login_url_allowed("https://snlib.go.kr"))
  assert_true(login_url_allowed("http://127.0.0.1:1234"))
  assert_true(login_url_allowed("http://localhost:1234"))
  assert_false(login_url_allowed("http://snlib.go.kr"))
  assert_false(login_url_allowed("http://localhost.example.invalid"))
  assert_false(login_url_allowed("http://127.0.0.1:1234@example.invalid"))
  assert_false(login_url_allowed("file:///etc/passwd"))
}

///|
test "header injection is rejected" {
  for
    headers in [
      { "X-Test": "value\r\nInjected: true" },
      { "Bad\nName": "value" },
      { "Bad:Name": "value" },
      { "X-Test": "value\u0000hidden" },
    ] {
    let rejected = try {
      ignore(header_block(headers))
      false
    } catch {
      @remote.Failed(_) => true
    }
    assert_true(rejected)
  }
  assert_eq(header_block({ "User-Agent": "snlib" }), "User-Agent: snlib")
}
