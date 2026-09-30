///|
test "login follows the Clojure three-request contract and verifies authentication" {
  let requests : Array[@remote.HttpRequest] = []
  let result = @remote.login({ user_id: "reader", password: "dummy-secret" }, request => {
    requests.push(request)
    let body = if requests.length() == 3 {
      "<li class='logout'><a href='/intro/memberLogout.do'>로그아웃</a></li>"
    } else {
      "<html>login</html>"
    }
    { status: 200, body }
  })
  assert_eq(result, LoginSuccess)
  assert_eq(requests.length(), 3)
  assert_eq(requests[0].path, @remote.login_page_path)
  assert_eq(requests[0].verb, Get)
  assert_eq(requests[1].path, @remote.login_submit_path)
  guard requests[1].verb is Post(form) else { fail("expected login form POST") }
  assert_eq(form["userId"], "reader")
  assert_eq(form["password"], "dummy-secret")
  assert_eq(
    form["returnUrl"],
    "aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw==",
  )
  assert_eq(requests[2].path, @remote.login_verify_path)
  assert_eq(requests[2].verb, Get)
}

///|
test "HTTP 200 alone never establishes authentication" {
  let unknown = @remote.login({ user_id: "reader", password: "dummy" }, _ => {
    status: 200,
    body: "<html>HTTP 200</html>",
  })
  assert_true(unknown is LoginFailure(Parse(_)))
  let rejected = @remote.login({ user_id: "reader", password: "dummy" }, _ => {
    status: 200,
    body: "<form id='redirectForm' action='/intro/memberLogin.do'></form>",
  })
  assert_eq(rejected, LoginRejected)
}

///|
test "login failures do not expose transport details or secrets" {
  let result = @remote.login({ user_id: "reader", password: "dummy-secret" }, _ => {
    raise @remote.Failed("accidental dummy-secret / private cookie")
  })
  assert_eq(result, LoginFailure(Network("HTTP request failed during login.")))
}

///|
test "account retrieval distinguishes rejection HTTP errors and parse errors" {
  assert_eq(
    @remote.my_info(_ => { status: 401, body: "denied" }),
    AccountLoggedOut,
  )
  assert_eq(
    @remote.my_info(_ => { status: 503, body: "unavailable" }),
    AccountFailure(HttpStatus(503)),
  )
  assert_true(
    @remote.my_info(_ => { status: 200, body: "<html>not account data</html>" })
    is AccountFailure(Parse(_)),
  )
}
