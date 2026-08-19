///|
pub(all) enum Outcome {
  Ok
  InvalidInput
  RequiresLogin
  NotImplemented
  Blocked
  RemoteError
  SubmitFailed
} derive(Eq, Debug)

///|
pub(all) struct CommandResult {
  outcome : Outcome
  code : String
  message : String
  data : String?
} derive(Eq, Debug)

///|
pub fn command_result(
  outcome : Outcome,
  code : String,
  message : String,
  data? : String,
) -> CommandResult {
  { outcome, code, message, data }
}
