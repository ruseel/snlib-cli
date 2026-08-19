///|
fn test_dir(name : String) -> String {
  "/tmp/snlib-store-\{@env.now()}-\{name}"
}

///|
test "session JSON round trips and is owner-only" {
  let dir = test_dir("session")
  let path = "\{dir}/nested/session.json"
  let session : @model.Session = {
    patron_id: "patron-1",
    authenticated_at_ms: 123456789UL,
    remembered: true,
    status: Active,
  }
  assert_true(@store.save_session_at(path, session))
  assert_eq(@store.load_session_at(path), Some(session))
  assert_eq(@store.file_mode_at(path), Some(384))
  @fs.remove_file(path)
  @fs.remove_dir("\{dir}/nested")
  @fs.remove_dir(dir)
}

///|
test "credentials round trip without appearing in API output" {
  let dir = test_dir("credentials")
  let path = "\{dir}/credentials.json"
  let credentials : @store.Credentials = {
    user_id: "reader",
    password: "secret-value",
  }
  assert_true(@store.save_credentials_at(path, credentials))
  assert_eq(@store.load_credentials_at(path), Some(credentials))
  assert_eq(@store.file_mode_at(path), Some(384))
  @fs.remove_file(path)
  @fs.remove_dir(dir)
}

///|
test "invalid and unsupported session JSON is ignored" {
  let dir = test_dir("invalid")
  @fs.create_dir(dir)
  let path = "\{dir}/session.json"
  @fs.write_string_to_file(path, "{\"version\":2}")
  assert_eq(@store.load_session_at(path), None)
  @fs.write_string_to_file(path, "not json")
  assert_eq(@store.load_session_at(path), None)
  @fs.remove_file(path)
  @fs.remove_dir(dir)
}

///|
test "legacy Clojure session migrates once to versioned JSON" {
  let dir = test_dir("legacy")
  @fs.create_dir(dir)
  let json_path = "\{dir}/session.json"
  let legacy_path = "\{dir}/session.edn"
  @fs.write_string_to_file(
    legacy_path, "{:saved-at-ms 123 :last-login-at-ms 456789 :cookies []}",
  )
  guard @store.load_session_with_legacy_at(json_path, legacy_path)
    is Some(session) else {
    fail("expected migrated session")
  }
  assert_eq(session.authenticated_at_ms, 456789UL)
  assert_true(@fs.path_exists(json_path))
  assert_false(@fs.path_exists(legacy_path))
  @fs.remove_file(json_path)
  @fs.remove_dir(dir)
}
