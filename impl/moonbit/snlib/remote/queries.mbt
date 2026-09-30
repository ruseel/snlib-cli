///|
pub let loan_history_path : String = "/intro/menu/10062/program/30021/mypage/loanHistoryList.do"

///|
pub let reservation_path : String = "/intro/menu/10061/program/30020/mypage/reservationStatusList.do"

///|
pub let interloan_status_path : String = "/intro/bandLillStatusList.do"

///|
pub let hope_list_path : String = "/intro/menu/10065/program/30011/mypage/hopeBookList.do"

///|
pub let hope_detail_path : String = "/intro/menu/10065/program/30011/mypage/hopeBookDetail.do"

///|
pub let basket_main_path : String = "/intro/menu/10057/program/30018/mypage/basketGroupMain.do"

///|
pub let basket_books_path : String = "/intro/menu/10057/program/30018/mypage/basketGroupBookList.do"

///|
pub(all) suberror CommandRemoteError {
  SessionRejected
  RequestFailure(RemoteError)
  InvalidPayload(String)
  SubmissionRejected(String)
} derive(Eq, Debug)

///|
fn checked_request(
  request : HttpRequest,
  transport : (HttpRequest) -> HttpResponse raise TransportError,
) -> String raise CommandRemoteError {
  let response = transport(request) catch {
    Failed(_) => raise RequestFailure(Network("HTTP request failed."))
  }
  if response.status == 401 || response.status == 403 {
    raise SessionRejected
  }
  if response.status < 200 || response.status >= 300 {
    raise RequestFailure(HttpStatus(response.status))
  }
  if @html.logged_out(response.body) {
    raise SessionRejected
  }
  response.body
}

///|
fn[T] parsed(
  body : String,
  parse : (String) -> T raise @model.ExtractionError,
) -> T raise CommandRemoteError {
  parse(body) catch {
    _ =>
      raise RequestFailure(
        Parse("The response does not contain the expected library data."),
      )
  }
}

///|
pub fn account_query(
  input : @model.CommandInput,
  transport : (HttpRequest) -> HttpResponse raise TransportError,
) -> @model.CommandData raise CommandRemoteError {
  let path = match input {
    LoanStatus(_) => login_verify_path
    LoanHistory => loan_history_path
    ReservationStatus => reservation_path
    InterloanStatus => interloan_status_path
    HopeBookList => hope_list_path
    HopeBookDetail(_) => hope_detail_path
    BasketList(_) => basket_main_path
    _ => raise InvalidPayload("Not an account query.")
  }
  let request = account_request(
    path,
    Get,
    if path == hope_detail_path {
      hope_list_path
    } else {
      "/intro/main/index.do"
    },
  )
  if input is HopeBookDetail(detail) {
    request.query["recKey"] = [detail.rec_key]
  }
  let body = checked_request(request, transport)
  match input {
    LoanStatus(options) =>
      @model.LoanStatusResult(
        parsed(body, html => {
          @html.extract_loan_status(html, options.include_history)
        }),
      )
    LoanHistory =>
      @model.LoanHistoryResult(parsed(body, @html.extract_loan_history))
    ReservationStatus =>
      @model.ReservationResult(parsed(body, @html.extract_reservations))
    InterloanStatus =>
      @model.InterloanStatusResult(parsed(body, @html.extract_interloan_status))
    HopeBookList =>
      @model.HopeBookListResult(parsed(body, @html.extract_hope_book_list))
    HopeBookDetail(_) =>
      @model.HopeBookDetailResult(parsed(body, @html.extract_hope_book_detail))
    BasketList(options) => {
      let group = parsed(body, @html.extract_basket_group)
      let key = match options.group_key {
        Some(key) => Some(key)
        None => group.key
      }
      let books = match key {
        Some(key) => {
          let body = checked_request(
            account_request(
              basket_books_path,
              Post({
                "searchGroupKey": key,
                "searchLibrary": "ALL",
                "searchKey": "TITLE",
                "searchValue": "",
              }),
              basket_main_path,
            ),
            transport,
          )
          parsed(body, @html.extract_basket_books)
        }
        None => []
      }
      @model.BasketResult({
        group_key: key,
        group_name: group.name,
        book_count: group.count,
        books,
        count: books.length(),
      })
    }
    _ => raise InvalidPayload("Not an account query.")
  }
}
