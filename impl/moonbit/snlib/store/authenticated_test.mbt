///|
test "cookie-bearing sessions round trip atomically with owner-only permissions" {
  let dir = "/tmp/snlib-auth-store-\{@env.now()}"
  let path = "\{dir}/session.json"
  let value : @store.AuthenticatedSession = {
    session: {
      patron_id: "fixture-reader",
      authenticated_at_ms: 123456789UL,
      remembered: true,
      status: Active,
    },
    base_url: "https://snlib.go.kr",
    cookies: [
      "#HttpOnly_snlib.go.kr\tFALSE\t/intro\tTRUE\t0\tJSESSIONID\tfictional-cookie",
    ],
  }
  assert_true(@store.save_authenticated_at(path, value))
  assert_eq(@store.load_authenticated_at(path), Some(value))
  assert_eq(@store.load_session_at(path), Some(value.session))
  assert_eq(@store.file_mode_at(path), Some(384))
  assert_false(@fs.read_file_to_string(path).contains("password"))
  @fs.remove_file(path)
  @fs.remove_dir(dir)
}

///|
test "legacy metadata alone is not an authenticated cookie session" {
  let dir = "/tmp/snlib-auth-legacy-\{@env.now()}"
  let path = "\{dir}/session.json"
  assert_true(
    @store.save_session_at(path, {
      patron_id: "reader",
      authenticated_at_ms: 123UL,
      remembered: true,
      status: Active,
    }),
  )
  assert_eq(@store.load_authenticated_at(path), None)
  @fs.remove_file(path)
  @fs.remove_dir(dir)
}
