///|
pub(all) struct AuthenticatedSession {
  session : @model.Session
  base_url : String
  cookies : Array[String]
} derive(Eq, Debug)

///|
struct AuthenticatedWire {
  version : Int
  patron_id : String
  authenticated_at_ms : Double
  remembered : Bool
  status : String
  base_url : String
  cookies : Array[String]
} derive(FromJson)

///|
fn valid_cookie_records(cookies : Array[String]) -> Bool {
  !cookies.is_empty() &&
  @utf8.encode(cookies.join("\n")).length() <= 65536 &&
  !cookies.any(line => {
    line.is_empty() ||
    line.contains("\n") ||
    line.contains("\r") ||
    line.contains("\u0000")
  })
}

///|
pub fn save_authenticated_at(
  path : String,
  value : AuthenticatedSession,
) -> Bool {
  if !valid_cookie_records(value.cookies) ||
    !value.session.remembered ||
    value.session.status != Active {
    return false
  }
  secure_write(
    path,
    Json::object({
      "version": Json::number(2.0, repr="2"),
      "patron_id": Json::string(value.session.patron_id),
      "authenticated_at_ms": Json::number(
        value.session.authenticated_at_ms.to_double(),
        repr=value.session.authenticated_at_ms.to_string(),
      ),
      "remembered": Json::boolean(true),
      "status": Json::string("active"),
      "base_url": Json::string(value.base_url),
      "cookies": Json::array(value.cookies.map(Json::string)),
    }).stringify(),
  )
}

///|
pub fn load_authenticated_at(path : String) -> AuthenticatedSession? {
  guard @fs.path_exists(path) else { return None }
  let input = @fs.read_file_to_string(path) catch { _ => return None }
  let json = @json.parse(input) catch { _ => return None }
  let decoded : AuthenticatedWire? = try @json.from_json(json) catch {
    _ => None
  } noraise {
    wire => Some(wire)
  }
  guard decoded is Some(wire) &&
    wire.version == 2 &&
    wire.remembered &&
    wire.status == "active" &&
    wire.authenticated_at_ms >= 0.0 &&
    wire.authenticated_at_ms < 9007199254740992.0 &&
    valid_cookie_records(wire.cookies) else {
    return None
  }
  Some({
    session: {
      patron_id: wire.patron_id,
      authenticated_at_ms: UInt64::trunc_double(wire.authenticated_at_ms),
      remembered: true,
      status: Active,
    },
    base_url: wire.base_url,
    cookies: wire.cookies,
  })
}

///|
pub fn save_authenticated(value : AuthenticatedSession) -> Bool {
  match session_path() {
    Some(path) => save_authenticated_at(path, value)
    None => false
  }
}

///|
pub fn load_authenticated() -> AuthenticatedSession? {
  match session_path() {
    Some(path) => load_authenticated_at(path)
    None => None
  }
}

///|
pub fn invalidate_session(now_ms : UInt64) -> Bool {
  save_session({
    patron_id: "",
    authenticated_at_ms: now_ms,
    remembered: false,
    status: Rejected,
  })
}
