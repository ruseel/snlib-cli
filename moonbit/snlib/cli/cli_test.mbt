///|
fn active_session() -> @model.Session {
  {
    patron_id: "patron",
    authenticated_at_ms: 1000UL,
    remembered: true,
    status: Active,
  }
}

///|
test "missing and expired sessions have distinct requires-login codes" {
  let missing = @cli.run("my-info", "{}", None, 2000UL)
  assert_eq(missing.outcome, RequiresLogin)
  assert_eq(missing.code, "session-missing")
  let expired = @cli.run(
    "my-info",
    "{}",
    Some(active_session()),
    1000UL + @model.session_duration_ms,
  )
  assert_eq(expired.outcome, RequiresLogin)
  assert_eq(expired.code, "session-expired")
  let active = @cli.run("my-info", "{}", Some(active_session()), 2000UL)
  assert_eq(active.outcome, NotImplemented)
}

///|
test "login and search are exempt from session preflight" {
  let login = @cli.run(
    "login",
    "{\"user_id\":\"u\",\"password\":\"p\"}",
    None,
    0UL,
  )
  assert_eq(login.outcome, NotImplemented)
  let search = @cli.run(
    "search-books",
    "{\"keyword\":\"moon\",\"manage_codes\":[],\"page\":1,\"per_page\":10,\"sort\":\"SIMILAR\",\"order\":\"DESC\"}",
    None,
    0UL,
  )
  assert_eq(search.outcome, NotImplemented)
}

///|
test "JSON envelope and malformed input are stable" {
  inspect(
    @cli.render(
      @model.command_result(RequiresLogin, "session-missing", "login"),
    ),
    content=(
      #|{"outcome":"requires-login","code":"session-missing","message":"login","data":null}
    ),
  )
  let malformed = @cli.run("search-books", "{", None, 0UL)
  assert_eq(malformed.outcome, InvalidInput)
  assert_eq(malformed.code, "malformed-json")
  assert_eq(@cli.run("nope", "{}", None, 0UL).code, "unknown-command")
}
