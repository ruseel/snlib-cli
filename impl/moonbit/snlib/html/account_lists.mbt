///|
fn article_rows(
  root : @dom.Element,
) -> Array[@dom.Element] raise @model.ExtractionError {
  let lists = descendants(root, e => has_class(e, "article-list"))
  guard !lists.is_empty() else { raise @model.MissingField("article-list") }
  lists
  .iter()
  .flat_map(list => {
    list.children
    .iter()
    .filter_map(node => {
      match node {
        @dom.Element(e) if e.tag == "li" &&
          !has_class(e, "checkArea") &&
          descendants(e, child => has_class(child, "emptyNote")).is_empty() =>
          Some(e)
        _ => None
      }
    })
  })
  .to_array()
}

///|
fn list_count(root : @dom.Element, fallback : Int) -> Int {
  descendants(root, e => has_class(e, "boardFilter"))
  .iter()
  .flat_map(e => descendants(e, child => has_class(child, "themeFC")).iter())
  .find_first(_ => true)
  .map(e => decimal(text(e)))
  .unwrap_or(fallback)
}

///|
fn row_title(row : @dom.Element) -> String {
  descendants(row, e => has_class(e, "title"))
  .iter()
  .find_first(_ => true)
  .map(text)
  .unwrap_or("")
}

///|
fn row_fields(row : @dom.Element) -> Map[String, String] {
  let fields : Map[String, String] = Map([])
  for span in descendants(row, e => e.tag == "span") {
    if text(span).split_once(":") is Some((key, content)) {
      fields[key.trim().to_owned()] = content.trim().to_owned()
    }
  }
  fields
}

///|
fn action_key(root : @dom.Element, function_name : String) -> String? {
  for element in descendants(root, e => e.attributes.contains("onclick")) {
    if element.attributes["onclick"].split_once(function_name + "(")
      is Some((_, rest)) &&
      rest.split_once(")") is Some((arg, _)) {
      let key = arg.trim().to_owned().trim(chars="'\"").to_owned()
      if key != "" && key.iter().all(c => c >= '0' && c <= '9') {
        return Some(key)
      }
    }
  }
  None
}

///|
pub fn extract_loan_history(
  html : String,
) -> @model.LoanHistoryData raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let loans = article_rows(root).map(row => {
    let fields = row_fields(row)
    let item : @model.LoanHistoryItem = {
      title: row_title(row),
      reg_no: fields.get("등록번호").unwrap_or(""),
      call_no: fields.get("청구기호").unwrap_or(""),
      library: fields.get("소장도서관").unwrap_or(""),
      room: fields.get("자료실").unwrap_or(""),
      return_status: fields.get("상태").unwrap_or(""),
      loan_date: fields.get("대출일").unwrap_or(""),
      return_date: fields.get("반납일").unwrap_or(""),
    }
    item
  })
  { loans, count: list_count(root, loans.length()) }
}

///|
pub fn extract_reservations(
  html : String,
) -> @model.ReservationData raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let reservations = article_rows(root).map(row => {
    let fields = row_fields(row)
    let item : @model.ReservationItem = {
      title: row_title(row),
      reg_no: fields.get("등록번호").unwrap_or(""),
      reservation_date: fields.get("예약일").unwrap_or(""),
      reservation_expiry: fields.get("예약만기일").unwrap_or(""),
      status: fields.get("상태").unwrap_or(""),
      library: fields.get("도서관").unwrap_or(""),
    }
    item
  })
  { reservations, count: list_count(root, reservations.length()) }
}

///|
pub fn extract_interloan_status(
  html : String,
) -> @model.InterloanStatusData raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let requests = article_rows(root).map(row => {
    let fields = row_fields(row)
    let item : @model.InterloanStatusItem = {
      title: row_title(row),
      reg_no: fields.get("등록번호").unwrap_or(""),
      call_no: fields.get("청구기호").unwrap_or(""),
      give_library: fields.get("제공도서관").unwrap_or(""),
      apl_library: fields.get("수령도서관").unwrap_or(""),
      status: fields.get("상태").unwrap_or(""),
      apply_date: fields.get("신청일").unwrap_or(""),
      applicant: fields.get("신청자").unwrap_or(""),
      cancel_key: action_key(row, "fnDooraeCancelProc").unwrap_or(""),
    }
    item
  })
  { requests, count: list_count(root, requests.length()) }
}

///|
pub fn extract_hope_book_list(
  html : String,
) -> @model.HopeBookListData raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let items = article_rows(root).map(row => {
    let fields = row_fields(row)
    let item : @model.HopeBookItem = {
      title: row_title(row),
      library: fields.get("도서관").unwrap_or(""),
      apply_date: fields.get("신청일").unwrap_or(""),
      status: fields.get("상태").unwrap_or(""),
      rec_key: action_key(row, "hopeBookDetail").unwrap_or(""),
    }
    item
  })
  { items, count: list_count(root, items.length()) }
}

///|
pub fn extract_hope_book_detail(
  html : String,
) -> @model.HopeBookDetailData raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let tables = descendants(root, e => has_class(e, "board-view"))
  guard !tables.is_empty() else { raise @model.MissingField("board-view") }
  let fields : Map[String, String] = Map([])
  for table in tables {
    for row in descendants(table, e => e.tag == "tr") {
      let headers = descendants(row, e => e.tag == "th")
      let cells = descendants(row, e => e.tag == "td")
      for i, header in headers {
        if cells.get(i) is Some(cell) {
          fields[text(header)] = text(cell)
        }
      }
    }
  }
  guard fields.contains("희망도서명") else {
    raise @model.MissingField("희망도서명")
  }
  {
    applicant: fields.get("신청자"),
    phone: fields.get("연락처"),
    email: fields.get("이메일"),
    library: fields.get("신청도서관"),
    title: fields.get("희망도서명"),
    author: fields.get("저자"),
    publisher: fields.get("출판사"),
    publish_year: fields.get("출판연도"),
    isbn: fields.get("ISBN"),
    price: fields.get("가격"),
    apply_date: fields.get("신청일"),
    status: fields.get("신청상태"),
    opinion: fields.get("신청사유"),
  }
}
