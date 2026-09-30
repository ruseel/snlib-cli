///|
pub let usage : String = "usage: snlib-cli <command>\nstdin: one JSON object\ncommands: login, search-books, loan-status, hope-book-request, interloan-request, interlibrary-loan-request, my-info, loan-history, reservation-status, interloan-status, hope-book-list, hope-book-detail, basket-list"

///|
struct LoginWire {
  user_id : String?
  password : String?
} derive(FromJson)

///|
struct SearchWire {
  keyword : String?
  manage_codes : Array[String]?
  page : Int?
  per_page : Int?
  sort : String?
  order : String?
} derive(FromJson)

///|
struct LoanStatusWire {
  include_history : Bool?
} derive(FromJson)

///|
struct InterloanWire {
  manage_code : String?
  reg_no : String?
  apl_lib_code : String?
  give_lib_code : String?
  user_key : String?
  appendix_apply_yn : String?
  submit : Bool?
} derive(FromJson)

///|
struct HopeWire {
  manage_code : String?
  request : Map[String, String]?
  submit : Bool?
} derive(FromJson)

///|
struct DetailWire {
  rec_key : String?
} derive(FromJson)

///|
struct BasketWire {
  group_key : String?
} derive(FromJson)

///|
fn outcome_name(outcome : @model.Outcome) -> String {
  match outcome {
    Ok => "ok"
    InvalidInput => "invalid-input"
    RequiresLogin => "requires-login"
    NotImplemented => "not-implemented"
    Blocked => "blocked"
    RemoteError => "remote-error"
    SubmitFailed => "submit-failed"
  }
}

///|
fn catalogue_book_json(book : @model.CatalogueBook) -> Json {
  Json::object({
    "title": Json::string(book.title),
    "author": Json::string(book.author),
    "publisher": Json::string(book.publisher),
    "publication_year": Json::string(book.publication_year),
    "manage_code": Json::string(book.manage_code),
    "registration_number": Json::string(book.registration_number),
  })
}

///|
fn optional_string_json(value : String?) -> Json {
  match value {
    Some(text) => Json::string(text)
    None => Json::null()
  }
}

///|
fn command_data_json(data : @model.CommandData) -> Json {
  match data {
    LoginResult(login) =>
      Json::object({
        "authenticated": Json::boolean(login.authenticated),
        "user_id": Json::string(login.user_id),
      })
    MyInfoResult(info) =>
      Json::object({
        "user_id": Json::string(info.user_id),
        "member_no": optional_string_json(info.member_no),
        "member_type": optional_string_json(info.member_type),
        "join_date": optional_string_json(info.join_date),
        "privacy_expiry_date": optional_string_json(info.privacy_expiry_date),
        "phone": optional_string_json(info.phone),
        "email": optional_string_json(info.email),
      })
    LoanStatusResult(data) => @json.to_json(data)
    LoanHistoryResult(data) => @json.to_json(data)
    ReservationResult(data) => @json.to_json(data)
    InterloanStatusResult(data) => @json.to_json(data)
    HopeBookListResult(data) => @json.to_json(data)
    HopeBookDetailResult(data) => @json.to_json(data)
    BasketResult(data) => @json.to_json(data)
    SubmissionResult(data) => @json.to_json(data)
    SearchBooksResult(search) =>
      Json::object({
        "items": Json::array(search.items.map(catalogue_book_json)),
        "page": Json::number(
          search.page.to_double(),
          repr=search.page.to_string(),
        ),
        "total_count": Json::number(
          search.total_count.to_double(),
          repr=search.total_count.to_string(),
        ),
      })
  }
}

///|
pub fn render(result : @model.CommandResult) -> String {
  let data = match result.data {
    Some(value) => command_data_json(value)
    None => Json::null()
  }
  Json::object({
    "outcome": Json::string(outcome_name(result.outcome)),
    "code": Json::string(result.code),
    "message": Json::string(result.message),
    "data": data,
  }).stringify()
}

///|
fn invalid(code : String, message : String) -> @model.CommandResult {
  @model.command_result(InvalidInput, code, message)
}

///|
fn[T : FromJson] decode(json : Json) -> T? {
  try @json.from_json(json) catch {
    _ => None
  } noraise {
    value => Some(value)
  }
}

///|
fn command_requires_login(command : String) -> Bool {
  command != "login" && command != "search-books"
}

///|
pub fn preflight(
  command : String,
  session : @model.Session?,
  now_ms : UInt64,
) -> @model.CommandResult? {
  if !command_requires_login(command) {
    return None
  }
  match session {
    None =>
      Some(
        @model.command_result(
          RequiresLogin,
          "session-missing",
          "No remembered session is available.",
        ),
      )
    Some(value) =>
      if value.is_usable(now_ms) {
        None
      } else {
        Some(
          @model.command_result(
            RequiresLogin,
            "session-expired",
            "The remembered session is expired or inactive.",
          ),
        )
      }
  }
}

///|
fn validate_input(command : String, json : Json) -> @model.CommandInput? {
  match command {
    "login" =>
      match (decode(json) : LoginWire?) {
        Some({ user_id: Some(user_id), password: Some(password) }) if user_id.trim() !=
          "" &&
          password.trim() != "" => Some(@model.Login({ user_id, password }))
        _ => None
      }
    "search-books" =>
      match (decode(json) : SearchWire?) {
        Some(
          { keyword: Some(keyword), manage_codes, page, per_page, sort, order }
        ) => {
          guard keyword.trim() != "" else { None }
          let page = page.unwrap_or(1)
          let per_page = per_page.unwrap_or(10)
          guard page >= 1 && per_page >= 1 else { None }
          let codes = manage_codes.unwrap_or([])
          guard codes.all(code => {
            @model.valid_manage_code(@model.normalize_manage_code(code))
          }) else {
            None
          }
          Some(
            @model.SearchBooks(
              @model.search_books_input(
                keyword,
                manage_codes=codes.map(@model.normalize_manage_code),
                page~,
                per_page~,
                sort=sort.unwrap_or("SIMILAR"),
                order=order.unwrap_or("DESC"),
              ),
            ),
          )
        }
        _ => None
      }
    "loan-status" =>
      (decode(json) : LoanStatusWire?).map(wire => {
        @model.LoanStatus(
          @model.loan_status_input(
            include_history=wire.include_history.unwrap_or(false),
          ),
        )
      })
    "hope-book-request" =>
      (decode(json) : HopeWire?).bind(wire => {
        guard wire.manage_code is None ||
          @model.valid_manage_code(
            @model.normalize_manage_code(wire.manage_code.unwrap()),
          ) else {
          None
        }
        Some(
          @model.HopeBookRequest({
            manage_code: wire.manage_code,
            request: wire.request.unwrap_or({}),
            submit: wire.submit.unwrap_or(false),
          }),
        )
      })
    "interloan-request" | "interlibrary-loan-request" => {
      guard (decode(json) : InterloanWire?)
        is Some(
          {
            manage_code: Some(manage),
            reg_no: Some(reg_no),
            apl_lib_code: Some(apl),
            give_lib_code,
            user_key,
            appendix_apply_yn,
            submit,
          }
        ) else {
        None
      }
      let manage = @model.normalize_manage_code(manage)
      let apl = @model.normalize_library_code(apl)
      guard @model.valid_manage_code(manage) &&
        @model.valid_library_code(apl) &&
        reg_no.trim() != "" &&
        ["Y", "N"].contains(appendix_apply_yn.unwrap_or("N")) else {
        None
      }
      guard give_lib_code is None ||
        @model.valid_library_code(
          @model.normalize_library_code(give_lib_code.unwrap()),
        ) else {
        None
      }
      Some(
        @model.InterloanRequest({
          manage_code: manage,
          reg_no,
          apl_lib_code: apl,
          give_lib_code,
          user_key,
          appendix_apply_yn: appendix_apply_yn.unwrap_or("N"),
          submit: submit.unwrap_or(false),
        }),
      )
    }
    "my-info" => Some(@model.MyInfo)
    "loan-history" => Some(@model.LoanHistory)
    "reservation-status" => Some(@model.ReservationStatus)
    "interloan-status" => Some(@model.InterloanStatus)
    "hope-book-list" => Some(@model.HopeBookList)
    "hope-book-detail" =>
      match (decode(json) : DetailWire?) {
        Some({ rec_key: Some(rec_key) }) if rec_key != "" &&
          rec_key.iter().all(c => c >= '0' && c <= '9') =>
          Some(@model.HopeBookDetail({ rec_key, }))
        _ => None
      }
    "basket-list" =>
      (decode(json) : BasketWire?).bind(wire => {
        guard wire.group_key is None ||
          (
            wire.group_key.unwrap() != "" &&
            wire.group_key.unwrap().iter().all(c => c >= '0' && c <= '9')
          ) else {
          None
        }
        Some(@model.BasketList({ group_key: wire.group_key }))
      })
    _ => None
  }
}

///|
fn known_command(command : String) -> Bool {
  match command {
    "login"
    | "search-books"
    | "loan-status"
    | "hope-book-request"
    | "interloan-request"
    | "interlibrary-loan-request"
    | "my-info"
    | "loan-history"
    | "reservation-status"
    | "interloan-status"
    | "hope-book-list"
    | "hope-book-detail"
    | "basket-list" => true
    _ => false
  }
}

///|
fn run_internal(
  command : String,
  input : String,
  session : @model.Session?,
  now_ms : UInt64,
  transport : ((@remote.HttpRequest) -> @remote.HttpResponse raise @remote.TransportError)?,
) -> @model.CommandResult {
  if command == "help" || command == "--help" {
    return @model.command_result(Ok, "usage", usage)
  }
  if !known_command(command) {
    return invalid("unknown-command", "Unknown command: \{command}. \{usage}")
  }
  let json = @json.parse(input) catch {
    _ => return invalid("malformed-json", "stdin must contain one JSON object.")
  }
  guard json is Object(_) else {
    return invalid("expected-object", "stdin must contain one JSON object.")
  }
  guard validate_input(command, json) is Some(command_input) else {
    return invalid(
      "invalid-command-input", "Input does not match the command schema.",
    )
  }
  if preflight(command, session, now_ms) is Some(result) {
    return result
  }
  guard transport is Some(send) else {
    return @model.command_result(
      RemoteError,
      "transport-unavailable",
      "Command input is valid, but no HTTP transport was supplied.",
    )
  }
  match command_input {
    SearchBooks(search) => @application.search_books(search, send)
    Login(login) => @application.login(login, send)
    MyInfo => @application.my_info(send)
    LoanStatus(_)
    | LoanHistory
    | ReservationStatus
    | InterloanStatus
    | HopeBookList
    | HopeBookDetail(_)
    | BasketList(_) => @application.account_query(command, command_input, send)
    HopeBookRequest(_) | InterloanRequest(_) =>
      @application.submit_request(command, command_input, send)
  }
}

///|
pub fn run(
  command : String,
  input : String,
  session : @model.Session?,
  now_ms : UInt64,
) -> @model.CommandResult {
  run_internal(command, input, session, now_ms, None)
}

///|
pub fn run_with_transport(
  command : String,
  input : String,
  session : @model.Session?,
  now_ms : UInt64,
  transport : (@remote.HttpRequest) -> @remote.HttpResponse raise @remote.TransportError,
) -> @model.CommandResult {
  run_internal(command, input, session, now_ms, Some(transport))
}
