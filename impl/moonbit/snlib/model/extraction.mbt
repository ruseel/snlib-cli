///|
pub(all) suberror ExtractionError {
  InvalidHtml(String)
  MissingField(String)
  SubmitFailed(String)
} derive(Eq, Debug)

///|
pub(all) struct CatalogueBook {
  title : String
  author : String
  publisher : String
  publication_year : String
  manage_code : String
  registration_number : String
} derive(Eq, Debug)

///|
pub(all) struct CatalogueSearch {
  total_count : Int
  items : Array[CatalogueBook]
} derive(Eq, Debug)

///|
pub(all) struct CurrentLoan {
  title : String
  loan_date_text : String
  due_date_text : String
  return_status_text : String
} derive(Eq, Debug)

///|
pub(all) struct HopeBookDefaults {
  email : String
  mobile_no_1 : String
  mobile_no_2 : String
  mobile_no_3 : String
  phone : String
  fields : Map[String, String]
} derive(Eq, Debug)

///|
pub(all) struct InterloanPreparation {
  manage_code : String
  registration_number : String
  providing_library_code : String
  appendix_apply : String
  user_key : String
} derive(Eq, Debug)

///|
pub(all) enum SubmissionEvidence {
  Submitted(String)
  SubmitFailed(String)
} derive(Eq, Debug)
