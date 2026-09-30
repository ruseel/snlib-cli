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
pub(all) struct SearchBooksData {
  items : Array[CatalogueBook]
  page : Int
  total_count : Int
} derive(Eq, Debug)

///|
pub(all) struct LoginData {
  authenticated : Bool
  user_id : String
} derive(Eq, Debug)

///|
pub(all) struct MyInfoData {
  user_id : String
  member_no : String?
  member_type : String?
  join_date : String?
  privacy_expiry_date : String?
  phone : String?
  email : String?
} derive(Eq, Debug)

///|
pub(all) enum CommandData {
  SearchBooksResult(SearchBooksData)
  LoginResult(LoginData)
  MyInfoResult(MyInfoData)
} derive(Eq, Debug)

///|
pub(all) struct CommandResult {
  outcome : Outcome
  code : String
  message : String
  data : CommandData?
} derive(Eq, Debug)

///|
pub fn command_result(
  outcome : Outcome,
  code : String,
  message : String,
  data? : CommandData,
) -> CommandResult {
  { outcome, code, message, data }
}
