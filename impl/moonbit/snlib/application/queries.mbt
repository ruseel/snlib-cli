///|
pub fn account_query(
  command : String,
  input : @model.CommandInput,
  transport : (@remote.HttpRequest) -> @remote.HttpResponse raise @remote.TransportError,
) -> @model.CommandResult {
  let data = @remote.account_query(input, transport) catch {
    error => return command_error(command, error)
  }
  @model.command_result(
    Ok,
    command + "-ok",
    "Library account data retrieved.",
    data~,
  )
}

///|
fn command_error(
  command : String,
  error : @remote.CommandRemoteError,
) -> @model.CommandResult {
  match error {
    SessionRejected =>
      @model.command_result(
        RequiresLogin,
        "session-rejected",
        "The server no longer accepts the remembered session.",
      )
    RequestFailure(Network(message)) =>
      @model.command_result(RemoteError, "http-request-failed", message)
    RequestFailure(HttpStatus(status)) =>
      @model.command_result(
        RemoteError,
        command + "-request-failed",
        "Request failed with status \{status}.",
      )
    RequestFailure(Parse(message)) =>
      @model.command_result(
        RemoteError,
        command + "-response-parse-failed",
        message,
      )
    InvalidPayload(message) =>
      @model.command_result(InvalidInput, "invalid-command-input", message)
    SubmissionRejected(message) =>
      @model.command_result(SubmitFailed, command + "-submit-failed", message)
  }
}
