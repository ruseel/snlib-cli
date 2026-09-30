///|
pub(all) struct LoanItem {
  title : String
  loan_date : String
  due_date : String
  return_status : String
  renewable : Bool?
} derive(Eq, Debug, ToJson)

///|
pub(all) struct LoanHistoryItem {
  title : String
  reg_no : String
  call_no : String
  library : String
  room : String
  return_status : String
  loan_date : String
  return_date : String
} derive(Eq, Debug, ToJson)

///|
pub(all) struct ReservationItem {
  title : String
  reg_no : String
  reservation_date : String
  reservation_expiry : String
  status : String
  library : String
} derive(Eq, Debug, ToJson)

///|
pub(all) struct InterloanStatusItem {
  title : String
  reg_no : String
  call_no : String
  give_library : String
  apl_library : String
  status : String
  apply_date : String
  applicant : String
  cancel_key : String
} derive(Eq, Debug, ToJson)

///|
pub(all) struct HopeBookItem {
  title : String
  library : String
  apply_date : String
  status : String
  rec_key : String
} derive(Eq, Debug, ToJson)

///|
pub(all) struct HopeBookDetailData {
  applicant : String?
  phone : String?
  email : String?
  library : String?
  title : String?
  author : String?
  publisher : String?
  publish_year : String?
  isbn : String?
  price : String?
  apply_date : String?
  status : String?
  opinion : String?
} derive(Eq, Debug, ToJson)

///|
pub(all) struct BasketBook {
  title : String
  author : String
  publisher : String
} derive(Eq, Debug, ToJson)

///|
pub(all) struct LoanStatusData {
  loans : Array[LoanItem]
  count : Int
} derive(Eq, Debug, ToJson)

///|
pub(all) struct LoanHistoryData {
  loans : Array[LoanHistoryItem]
  count : Int
} derive(Eq, Debug, ToJson)

///|
pub(all) struct ReservationData {
  reservations : Array[ReservationItem]
  count : Int
} derive(Eq, Debug, ToJson)

///|
pub(all) struct InterloanStatusData {
  requests : Array[InterloanStatusItem]
  count : Int
} derive(Eq, Debug, ToJson)

///|
pub(all) struct HopeBookListData {
  items : Array[HopeBookItem]
  count : Int
} derive(Eq, Debug, ToJson)

///|
pub(all) struct BasketData {
  group_key : String?
  group_name : String?
  book_count : Int?
  books : Array[BasketBook]
  count : Int
} derive(Eq, Debug, ToJson)

///|
pub(all) struct SubmissionData {
  prepared_payload : Map[String, String]
  submit_attempted : Bool
  result_message : String?
} derive(Eq, Debug, ToJson)
