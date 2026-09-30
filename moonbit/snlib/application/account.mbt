///|
pub fn login(
  input : @model.LoginInput,
  transport : (@remote.HttpRequest) -> @remote.HttpResponse raise @remote.TransportError,
) -> @model.CommandResult {
  match @remote.login(input, transport) {
    LoginSuccess =>
      @model.command_result(
        Ok,
        "login-ok",
        "Authentication confirmed.",
        data=LoginResult({ authenticated: true, user_id: input.user_id }),
      )
    LoginRejected =>
      @model.command_result(
        RequiresLogin,
        "login-rejected",
        "Authentication was rejected.",
        data=LoginResult({ authenticated: false, user_id: input.user_id }),
      )
    LoginFailure(Network(message)) =>
      @model.command_result(RemoteError, "http-request-failed", message)
    LoginFailure(HttpStatus(status)) =>
      @model.command_result(
        RemoteError,
        "login-request-failed",
        "Login request failed with status \{status}.",
      )
    LoginFailure(Parse(message)) =>
      @model.command_result(RemoteError, "login-response-parse-failed", message)
  }
}

///|
pub fn my_info(
  transport : (@remote.HttpRequest) -> @remote.HttpResponse raise @remote.TransportError,
) -> @model.CommandResult {
  match @remote.my_info(transport) {
    AccountInfo(info) =>
      @model.command_result(
        Ok,
        "my-info-ok",
        "Account information retrieved.",
        data=MyInfoResult(info),
      )
    AccountLoggedOut =>
      @model.command_result(
        RequiresLogin,
        "session-rejected",
        "The server no longer accepts the remembered session.",
      )
    AccountFailure(Network(message)) =>
      @model.command_result(RemoteError, "http-request-failed", message)
    AccountFailure(HttpStatus(status)) =>
      @model.command_result(
        RemoteError,
        "my-info-request-failed",
        "Account request failed with status \{status}.",
      )
    AccountFailure(Parse(message)) =>
      @model.command_result(
        RemoteError,
        "my-info-response-parse-failed",
        message,
      )
  }
}
