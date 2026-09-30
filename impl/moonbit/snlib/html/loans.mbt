///|
fn renewable(status : String) -> Bool? {
  let token = status.trim().to_upper().to_owned()
  if token.contains("불가") ||
    ["N", "NO", "FALSE", "X", "불가능"].contains(token) {
    Some(false)
  } else if token.contains("가능") ||
    ["Y", "YES", "TRUE", "O"].contains(token) {
    Some(true)
  } else {
    None
  }
}

///|
fn returned(status : String) -> Bool {
  let token = status.split(" ").join("")
  ["반납완료", "반납됨", "대출종료", "회수완료"].any(word => {
    token.contains(word)
  })
}

///|
pub fn extract_loan_status(
  html : String,
  include_history : Bool,
) -> @model.LoanStatusData raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let loans : Array[@model.LoanItem] = []
  for table in descendants(root, e => e.tag == "table") {
    let headers = descendants(table, e => e.tag == "th").map(text)
    let indexes : Map[String, Int] = Map([])
    for i, header in headers {
      let key = if ["서명", "자료명", "도서명", "제목"].any(s => {
          header.contains(s)
        }) {
        "title"
      } else if header.contains("대출일") {
        "loan_date"
      } else if ["반납예정", "예정반납", "반납일"].any(s => {
          header.contains(s)
        }) {
        "due_date"
      } else if header.contains("상태") {
        "return_status"
      } else if header.contains("연장") || header.contains("재대출") {
        "renewable"
      } else {
        ""
      }
      if key != "" && !indexes.contains(key) {
        indexes[key] = i
      }
    }
    if !indexes.contains("title") {
      continue
    }
    for row in descendants(table, e => e.tag == "tr") {
      let cells = descendants(row, e => e.tag == "td").map(text)
      if cells.is_empty() {
        continue
      }
      if cells.length() == 1 &&
        ["없습니다", "존재하지"].any(s => cells[0].contains(s)) {
        continue
      }
      fn cell(key : String) -> String {
        match indexes.get(key) {
          Some(i) => cells.get(i).unwrap_or("")
          None => ""
        }
      }
      let item : @model.LoanItem = {
        title: cell("title"),
        loan_date: cell("loan_date"),
        due_date: cell("due_date"),
        return_status: cell("return_status"),
        renewable: renewable(
          if cell("renewable") != "" {
            cell("renewable")
          } else {
            cell("return_status")
          },
        ),
      }
      loans.push(item)
    }
  }
  if loans.is_empty() {
    let lists = descendants(root, e => has_class(e, "article-list"))
    if !lists.is_empty() {
      for row in article_rows(root) {
        let fields = row_fields(row)
        let status = fields.get("상태").unwrap_or("")
        loans.push({
          title: row_title(row),
          loan_date: fields.get("대출일").unwrap_or(""),
          due_date: fields.get("반납예정일").unwrap_or(""),
          return_status: status,
          renewable: renewable(status),
        })
      }
    } else if descendants(root, e => e.tag == "table").is_empty() ||
      !text(root).contains("없습니다") {
      raise @model.MissingField("loan status list")
    }
  }
  let loans = if include_history {
    loans
  } else {
    loans.filter(loan => !returned(loan.return_status))
  }
  { loans, count: loans.length() }
}
