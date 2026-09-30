///|
extern "C" fn getchar() -> Int = "getchar"

///|
fn read_stdin() -> String {
  let bytes : Array[Byte] = []
  for ;; {
    let value = getchar()
    if value < 0 {
      break
    }
    bytes.push(value.to_byte())
  }
  @utf8.decode_lossy(Bytes::from_array(bytes))
}

///|
fn main {
  let args = @env.args()
  let command = if args.length() > 1 { args[1] } else { "help" }
  let input = if command == "help" || command == "--help" {
    "{}"
  } else {
    read_stdin()
  }
  let base_url = @env.get_env_var("SNLIB_BASE_URL")
    .unwrap_or(@remote.default_base_url)
    .trim_end(chars="/")
    .to_owned()
  let authenticated_command = command != "login" &&
    command != "search-books" &&
    command != "help" &&
    command != "--help"
  let saved = if authenticated_command {
    @store.load_authenticated()
  } else {
    None
  }
  let matching = match saved {
    Some(value) if value.base_url == base_url => Some(value)
    _ => None
  }
  let session = matching.map(value => value.session)
  // New login and public search never inherit another patron's cookies.
  let cookies = @ref.new(
    if authenticated_command {
      matching.map(value => value.cookies).unwrap_or([])
    } else {
      []
    },
  )
  let now_ms = @env.now()
  let mut submission_posted = false
  let mut result = @cli.run_with_transport(command, input, session, now_ms, request => {
    if request.verb is Post(_) &&
      (
        request.path == @remote.interloan_submit_path ||
        request.path == @remote.hope_submit_path
      ) {
      submission_posted = true
    }
    let response = @http_session.exchange(request, cookies.val, base_url~)
    cookies.val = response.cookies
    response.response
  })
  if command == "login" && result.outcome == Ok {
    let persisted = match result.data {
      Some(LoginResult(data)) =>
        @store.save_authenticated({
          session: {
            patron_id: data.user_id,
            authenticated_at_ms: now_ms,
            remembered: true,
            status: Active,
          },
          base_url,
          cookies: cookies.val,
        })
      _ => false
    }
    if !persisted {
      ignore(@store.invalidate_session(now_ms))
      result = @model.command_result(
        RemoteError,
        "session-save-failed",
        "Authentication succeeded, but a reusable session could not be saved.",
      )
    }
  } else if (
      command == "login" &&
      (result.outcome == RequiresLogin || result.outcome == RemoteError)
    ) ||
    (authenticated_command && result.code == "session-rejected") {
    if !@store.invalidate_session(now_ms) {
      result = @model.command_result(
        RemoteError,
        "session-save-failed",
        "The previous session could not be invalidated.",
      )
    }
  } else if authenticated_command &&
    (result.outcome == Ok || result.outcome == SubmitFailed) {
    // Persist rotated cookies without extending the local three-hour TTL.
    if matching is Some(value) &&
      !@store.save_authenticated({
        session: value.session,
        base_url,
        cookies: cookies.val,
      }) {
      result = @model.command_result(
        RemoteError,
        "session-save-failed",
        if submission_posted {
          "The submission was attempted, but the refreshed session could not be saved. Do not retry blindly; check request status first."
        } else {
          "The operation completed, but the refreshed session could not be saved."
        },
      )
    }
  }
  if authenticated_command &&
    result.code == "session-missing" &&
    saved is Some(value) &&
    value.base_url != base_url {
    result = @model.command_result(
      RequiresLogin,
      "session-origin-mismatch",
      "Log in again for the selected server; saved cookies belong to another server.",
    )
  }
  println(@cli.render(result))
}
