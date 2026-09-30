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
test "legacy cookies remain untouched and are not native authentication" {
  let dir = test_dir("legacy")
  @fs.create_dir(dir)
  let json_path = "\{dir}/session.json"
  let legacy_path = "\{dir}/session.edn"
  let legacy = "{:last-login-at-ms 456789 :cookies [{:name \"JSESSIONID\" :value \"fictional\"}]}"
  @fs.write_string_to_file(legacy_path, legacy)
  assert_eq(@store.load_session_with_legacy_at(json_path, legacy_path), None)
  assert_false(@fs.path_exists(json_path))
  assert_eq(@fs.read_file_to_string(legacy_path), legacy)
  // An invalid native file must not trigger destructive legacy recovery either.
  @fs.write_string_to_file(json_path, "not json")
  assert_eq(@store.load_session_with_legacy_at(json_path, legacy_path), None)
  assert_eq(@fs.read_file_to_string(json_path), "not json")
  assert_eq(@fs.read_file_to_string(legacy_path), legacy)
  @fs.remove_file(json_path)
  @fs.remove_file(legacy_path)
  @fs.remove_dir(dir)
}

///|
test "native session takes precedence without modifying legacy cookies" {
  let dir = test_dir("native-and-legacy")
  let json_path = "\{dir}/session.json"
  let legacy_path = "\{dir}/session.edn"
  let session : @model.Session = {
    patron_id: "native",
    authenticated_at_ms: 1000UL,
    remembered: true,
    status: Active,
  }
  assert_true(@store.save_session_at(json_path, session))
  @fs.write_string_to_file(legacy_path, "legacy cookie data")
  assert_eq(
    @store.load_session_with_legacy_at(json_path, legacy_path),
    Some(session),
  )
  assert_eq(@fs.read_file_to_string(legacy_path), "legacy cookie data")
  @fs.remove_file(json_path)
  @fs.remove_file(legacy_path)
  @fs.remove_dir(dir)
}
