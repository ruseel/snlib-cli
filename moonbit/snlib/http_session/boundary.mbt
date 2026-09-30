///|
#borrow(url, headers, form, cookies)
extern "C" fn request_native(
  url : Bytes,
  url_len : Int,
  headers : Bytes,
  headers_len : Int,
  form : Bytes,
  form_len : Int,
  is_post : Int,
  cookies : Bytes,
  cookies_len : Int,
) -> Bytes = "snlib_http_request"

///|
pub(all) struct Exchange {
  response : @remote.HttpResponse
  cookies : Array[String]
}

///|
fn percent_encode(value : String) -> String {
  let hex : Array[Char] = [
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
  ]
  let result = StringBuilder::new()
  for byte in @utf8.encode(value) {
    let value = byte.to_int()
    if (value >= 65 && value <= 90) ||
      (value >= 97 && value <= 122) ||
      (value >= 48 && value <= 57) ||
      value == 45 ||
      value == 46 ||
      value == 95 ||
      value == 126 {
      result.write_char(value.unsafe_to_char())
    } else {
      result.write_char('%')
      result.write_char(hex[value / 16])
      result.write_char(hex[value % 16])
    }
  }
  result.to_string()
}

///|
fn request_url(request : @remote.HttpRequest, base_url : String) -> String {
  let query : Array[String] = []
  let entries = request.query.to_array()
  entries.sort_by((a, b) => a.0.compare(b.0))
  for entry in entries {
    let (key, values) = entry
    for value in values {
      query.push("\{percent_encode(key)}=\{percent_encode(value)}")
    }
  }
  let separator = if query.is_empty() { "" } else { "?" }
  "\{base_url}\{request.path}\{separator}\{query.join("&")}"
}

///|
fn header_block(
  headers : Map[String, String],
) -> String raise @remote.TransportError {
  let lines : Array[String] = []
  for name, value in headers {
    if name.is_empty() ||
      name.contains(":") ||
      name.contains("\r") ||
      name.contains("\n") ||
      name.contains("\u0000") ||
      value.contains("\r") ||
      value.contains("\n") ||
      value.contains("\u0000") {
      raise @remote.Failed("Invalid HTTP header.")
    }
    lines.push("\{name}: \{value}")
  }
  lines.join("\n")
}

///|
fn login_url_allowed(base : String) -> Bool {
  if base.has_prefix("https://") {
    return true
  }
  guard base.split_once("http://") is Some(("", rest)) else { return false }
  let authority = rest.split("/").to_array()[0]
  !authority.contains("@") &&
  (
    authority == "127.0.0.1" ||
    authority.has_prefix("127.0.0.1:") ||
    authority == "localhost" ||
    authority.has_prefix("localhost:")
  )
}

///|
// Stateless public discovery. Session-aware commands use exchange instead.
pub fn send(
  request : @remote.HttpRequest,
  base_url? : String,
) -> @remote.HttpResponse raise @remote.TransportError {
  exchange(request, [], base_url?).response
}

///|
// libcurl applies cookie domain/path/expiry/Secure rules on every request.
pub fn exchange(
  request : @remote.HttpRequest,
  cookies : Array[String],
  base_url? : String,
) -> Exchange raise @remote.TransportError {
  let base = match base_url {
    Some(value) => value.trim_end(chars="/").to_owned()
    None => request.base_url.trim_end(chars="/").to_owned()
  }
  if !(base.has_prefix("https://") || base.has_prefix("http://")) ||
    base.contains("\u0000") ||
    request.path.contains("\u0000") {
    raise @remote.Failed("HTTP base URL must use http:// or https://.")
  }
  let (is_post, form) = match request.verb {
    Get => (0, "")
    Post(fields) => {
      if !login_url_allowed(base) {
        raise @remote.Failed(
          "Login requires HTTPS outside loopback test servers.",
        )
      }
      let parts = fields
        .to_array()
        .map(entry => {
          let (name, value) = entry
          "\{percent_encode(name)}=\{percent_encode(value)}"
        })
      (1, parts.join("&"))
    }
  }
  if cookies.any(line => {
      line.contains("\n") || line.contains("\r") || line.contains("\u0000")
    }) {
    raise @remote.Failed("Invalid session cookie record.")
  }
  let url = @utf8.encode(request_url(request, base))
  let headers = @utf8.encode(header_block(request.headers))
  let form = @utf8.encode(form)
  let cookie_bytes = @utf8.encode(cookies.join("\n"))
  if cookie_bytes.length() > 65536 {
    raise @remote.Failed("Session cookies exceed 64 KiB limit.")
  }
  let packet = request_native(
    url,
    url.length(),
    headers,
    headers.length(),
    form,
    form.length(),
    is_post,
    cookie_bytes,
    cookie_bytes.length(),
  )
  // Native wire: status (u32 LE), cookie byte length (u32 LE), cookies, body.
  // A zero status carries a transport error instead of an HTTP response.
  if packet.length() < 8 {
    raise @remote.Failed("Invalid native HTTP response.")
  }
  let status = packet[0].to_int() +
    (packet[1].to_int() << 8) +
    (packet[2].to_int() << 16) +
    (packet[3].to_int() << 24)
  let cookie_len = packet[4].to_int() +
    (packet[5].to_int() << 8) +
    (packet[6].to_int() << 16) +
    (packet[7].to_int() << 24)
  if cookie_len < 0 || cookie_len > 65536 || cookie_len > packet.length() - 8 {
    raise @remote.Failed("Invalid native cookie response.")
  }
  let body = @utf8.decode_lossy(packet[8 + cookie_len:])
  if status == 0 {
    raise @remote.Failed(body)
  }
  let exported = @utf8.decode_lossy(packet[8:8 + cookie_len])
    .split("\n")
    .filter(line => !line.is_empty())
    .map(line => line.to_owned())
    .to_array()
  { response: { status, body }, cookies: exported }
}
