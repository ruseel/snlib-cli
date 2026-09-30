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
  assert_eq(active.outcome, RemoteError)
}

///|
test "login and search are exempt from session preflight" {
  let login = @cli.run(
    "login",
    "{\"user_id\":\"u\",\"password\":\"p\"}",
    None,
    0UL,
  )
  assert_eq(login.outcome, RemoteError)
  let search = @cli.run(
    "search-books",
    "{\"keyword\":\"moon\",\"manage_codes\":[],\"page\":1,\"per_page\":10,\"sort\":\"SIMILAR\",\"order\":\"DESC\"}",
    None,
    0UL,
  )
  assert_eq(search.outcome, RemoteError)
  let blank = @cli.run("search-books", "{\"keyword\":\"  \"}", None, 0UL)
  assert_eq(blank.outcome, InvalidInput)
  assert_eq(blank.code, "invalid-command-input")
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

///|
test "search CLI renders fixture result as structured JSON data" {
  let html = @fs.read_file_to_string(
    "../../test/fixtures/snlib/search-books/hope-book-target.html",
  )
  let result = @cli.run_with_transport(
    "search-books",
    "{\"keyword\":\"삼국지 2\"}",
    None,
    0UL,
    _ => ({ status: 200, body: html } : @remote.HttpResponse),
  )
  assert_eq(result.outcome, Ok)
  inspect(
    @cli.render(result),
    content=(
      #|{"outcome":"ok","code":"search-ok","message":"Catalogue search completed.","data":{"items":[{"title":"삼국지 2, 적벽대전","author":"나관중 지음 ; 테스트번역 옮김","publisher":"가상출판사","publication_year":"2024","manage_code":"MP","registration_number":"FIC000000202"}],"page":1,"total_count":1}}
    ),
  )
}
