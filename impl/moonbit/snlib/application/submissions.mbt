///|
pub fn submit_request(
  command : String,
  input : @model.CommandInput,
  transport : (@remote.HttpRequest) -> @remote.HttpResponse raise @remote.TransportError,
) -> @model.CommandResult {
  let data = @remote.submit_request(input, transport) catch {
    error => return command_error(command, error)
  }
  let code = command + (if data.submit_attempted { "-ok" } else { "-prepared" })
  let message = if data.submit_attempted {
    "Submission confirmed."
  } else {
    "Prepared only; set submit=true after confirming the payload."
  }
  @model.command_result(Ok, code, message, data=SubmissionResult(data))
}
