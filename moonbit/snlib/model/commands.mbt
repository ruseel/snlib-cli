///|
pub(all) struct LoginInput {
  user_id : String
  password : String
} derive(Eq, Debug)

///|
pub(all) struct SearchBooksInput {
  keyword : String
  manage_codes : Array[String]
  page : Int
  per_page : Int
  sort : String
  order : String
} derive(Eq, Debug)

///|
pub fn search_books_input(
  keyword : String,
  manage_codes? : Array[String] = [],
  page? : Int = 1,
  per_page? : Int = 10,
  sort? : String = "SIMILAR",
  order? : String = "DESC",
) -> SearchBooksInput {
  { keyword, manage_codes, page, per_page, sort, order }
}

///|
pub(all) struct LoanStatusInput {
  include_history : Bool
} derive(Eq, Debug)

///|
pub fn loan_status_input(include_history? : Bool = false) -> LoanStatusInput {
  { include_history, }
}

///|
pub(all) struct InterloanRequestInput {
  manage_code : String
  reg_no : String
  apl_lib_code : String
  give_lib_code : String?
  user_key : String?
  appendix_apply_yn : String
} derive(Eq, Debug)

///|
pub fn interloan_request_input(
  manage_code : String,
  reg_no : String,
  apl_lib_code : String,
  give_lib_code? : String,
  user_key? : String,
  appendix_apply_yn? : String = "N",
) -> InterloanRequestInput {
  {
    manage_code,
    reg_no,
    apl_lib_code,
    give_lib_code,
    user_key,
    appendix_apply_yn,
  }
}

///|
pub(all) struct HopeBookRequestInput {
  manage_code : String?
  request : Map[String, String]
} derive(Eq, Debug)

///|
pub(all) struct HopeBookDetailInput {
  rec_key : String
} derive(Eq, Debug)

///|
pub(all) struct BasketListInput {
  group_key : String?
} derive(Eq, Debug)

///|
pub(all) enum CommandInput {
  Login(LoginInput)
  SearchBooks(SearchBooksInput)
  LoanStatus(LoanStatusInput)
  HopeBookRequest(HopeBookRequestInput)
  InterloanRequest(InterloanRequestInput)
  MyInfo
  LoanHistory
  ReservationStatus
  InterloanStatus
  HopeBookList
  HopeBookDetail(HopeBookDetailInput)
  BasketList(BasketListInput)
} derive(Eq, Debug)
