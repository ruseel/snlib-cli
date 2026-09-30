///|
test "blank credentials and search keywords fail validation" {
  for
    input in [
      "{\"user_id\":\"\",\"password\":\"p\"}", "{\"user_id\":\"u\",\"password\":\"   \"}",
      "{\"user_id\":\"   \",\"password\":\"p\"}",
    ] {
    assert_eq(@cli.run("login", input, None, 0UL).code, "invalid-command-input")
  }
  for input in ["{\"keyword\":\"\"}", "{\"keyword\":\"   \"}"] {
    assert_eq(
      @cli.run("search-books", input, None, 0UL).code,
      "invalid-command-input",
    )
  }
}

///|
test "search pagination must be positive and defaults still work" {
  for
    input in [
      "{\"keyword\":\"moon\",\"page\":0}", "{\"keyword\":\"moon\",\"page\":-1}",
      "{\"keyword\":\"moon\",\"per_page\":0}", "{\"keyword\":\"moon\",\"per_page\":-1}",
    ] {
    assert_eq(
      @cli.run("search-books", input, None, 0UL).code,
      "invalid-command-input",
    )
  }
  assert_eq(
    @cli.run("search-books", "{\"keyword\":\"moon\"}", None, 0UL).outcome,
    RemoteError,
  )
  assert_eq(
    @cli.run(
      "search-books",
      "{\"keyword\":\"moon\",\"page\":1,\"per_page\":1}",
      None,
      0UL,
    ).outcome,
    RemoteError,
  )
}

///|
test "future sessions fail CLI preflight" {
  let session : @model.Session = {
    patron_id: "fictional",
    authenticated_at_ms: 2000UL,
    remembered: true,
    status: Active,
  }
  assert_eq(
    @cli.run("my-info", "{}", Some(session), 1000UL).code,
    "session-expired",
  )
}
