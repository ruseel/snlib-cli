///|
pub(all) struct Credentials {
  user_id : String
  password : String
} derive(Eq, Debug)

///|
struct SessionWire {
  version : Int
  patron_id : String
  authenticated_at_ms : Double
  remembered : Bool
  status : String
} derive(FromJson)

///|
struct CredentialsWire {
  version : Int
  user_id : String
  password : String
} derive(FromJson)

///|
#borrow(path, content)
extern "C" fn secure_write_native(
  path : Bytes,
  path_len : Int,
  content : Bytes,
  content_len : Int,
) -> Int = "snlib_store_secure_write"

///|
#borrow(path)
extern "C" fn file_mode_native(path : Bytes, path_len : Int) -> Int = "snlib_store_file_mode"

///|
fn secure_write(path : String, content : String) -> Bool {
  let path_bytes = @utf8.encode(path)
  let content_bytes = @utf8.encode(content)
  secure_write_native(
    path_bytes,
    path_bytes.length(),
    content_bytes,
    content_bytes.length(),
  ) ==
  0
}

///|
pub fn config_dir() -> String? {
  match @env.get_env_var("XDG_CONFIG_HOME") {
    Some(path) if path.trim() != "" => Some("\{path}/snlib-cli")
    _ => @env.get_env_var("HOME").map(path => "\{path}/.config/snlib-cli")
  }
}

///|
pub fn session_path() -> String? {
  config_dir().map(path => "\{path}/session.json")
}

///|
pub fn credentials_path() -> String? {
  config_dir().map(path => "\{path}/credentials.json")
}

///|
pub fn save_session_at(path : String, session : @model.Session) -> Bool {
  let status = match session.status {
    Active => "active"
    Expired => "expired"
    Rejected => "rejected"
  }
  secure_write(
    path,
    Json::object({
      "version": Json::number(1.0, repr="1"),
      "patron_id": Json::string(session.patron_id),
      "authenticated_at_ms": Json::number(
        session.authenticated_at_ms.to_double(),
        repr=session.authenticated_at_ms.to_string(),
      ),
      "remembered": Json::boolean(session.remembered),
      "status": Json::string(status),
    }).stringify(),
  )
}

///|
pub fn load_session_at(path : String) -> @model.Session? {
  guard @fs.path_exists(path) else { return None }
  let input = @fs.read_file_to_string(path) catch { _ => return None }
  let json = @json.parse(input) catch { _ => return None }
  let decoded : SessionWire? = try @json.from_json(json) catch {
    _ => None
  } noraise {
    wire => Some(wire)
  }
  guard decoded is Some(wire) &&
    (wire.version == 1 || wire.version == 2) &&
    wire.authenticated_at_ms >= 0.0 &&
    wire.authenticated_at_ms < 9007199254740992.0 else {
    return None
  }
  let status = match wire.status {
    "active" => @model.Active
    "expired" => @model.Expired
    "rejected" => @model.Rejected
    _ => return None
  }
  Some({
    patron_id: wire.patron_id,
    authenticated_at_ms: UInt64::trunc_double(wire.authenticated_at_ms),
    remembered: wire.remembered,
    status,
  })
}

///|
/// Legacy EDN cookies cannot be represented by this metadata-only store.
/// Retain this compatibility entry point, but never migrate or delete legacy data.
pub fn load_session_with_legacy_at(
  json_path : String,
  _legacy_path : String,
) -> @model.Session? {
  load_session_at(json_path)
}

///|
pub fn save_credentials_at(path : String, credentials : Credentials) -> Bool {
  secure_write(
    path,
    Json::object({
      "version": Json::number(1.0, repr="1"),
      "user_id": Json::string(credentials.user_id),
      "password": Json::string(credentials.password),
    }).stringify(),
  )
}

///|
pub fn save_session(session : @model.Session) -> Bool {
  match session_path() {
    Some(path) => save_session_at(path, session)
    None => false
  }
}

///|
pub fn save_credentials(credentials : Credentials) -> Bool {
  match credentials_path() {
    Some(path) => save_credentials_at(path, credentials)
    None => false
  }
}

///|
pub fn load_credentials_at(path : String) -> Credentials? {
  guard @fs.path_exists(path) else { return None }
  let input = @fs.read_file_to_string(path) catch { _ => return None }
  let json = @json.parse(input) catch { _ => return None }
  let decoded : CredentialsWire? = try @json.from_json(json) catch {
    _ => None
  } noraise {
    wire => Some(wire)
  }
  guard decoded is Some(wire) && wire.version == 1 else { return None }
  Some({ user_id: wire.user_id, password: wire.password })
}

///|
pub fn load_credentials() -> Credentials? {
  match credentials_path() {
    Some(path) => load_credentials_at(path)
    None => None
  }
}

///|
pub fn load_session() -> @model.Session? {
  match session_path() {
    Some(path) => load_session_at(path)
    None => None
  }
}

///|
pub fn file_mode_at(path : String) -> Int? {
  let bytes = @utf8.encode(path)
  let mode = file_mode_native(bytes, bytes.length())
  if mode < 0 {
    None
  } else {
    Some(mode)
  }
}
