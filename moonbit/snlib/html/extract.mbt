///|
fn text(element : @dom.Element) -> String {
  let out = StringBuilder()
  fn walk(node : @dom.Node, out : StringBuilder) -> Unit {
    match node {
      @dom.Text(value) => out.write_string(value)
      @dom.Element(child) => child.children.each(n => walk(n, out))
    }
  }
  element.children.each(node => walk(node, out))
  out
  .to_string()
  .replace(old="\r", new=" ")
  .replace(old="\n", new=" ")
  .split(" ")
  .filter(s => !s.is_empty())
  .join(" ")
  .trim()
  .to_owned()
}

///|
fn descendants(
  element : @dom.Element,
  predicate : (@dom.Element) -> Bool,
) -> Array[@dom.Element] {
  let found = []
  fn walk(current : @dom.Element) -> Unit {
    if predicate(current) {
      found.push(current)
    }
    current.children.each(node => if node is @dom.Element(child) { walk(child) })
  }
  walk(element)
  found
}

///|
fn has_class(element : @dom.Element, name : String) -> Bool {
  element.classes.contains(name)
}

///|
fn value(element : @dom.Element) -> String {
  element.attributes.get("value").unwrap_or("")
}

///|
fn field(root : @dom.Element, name : String) -> String? {
  descendants(root, e => e.attributes.get("name") == Some(name))
  .iter()
  .find_first(_ => true)
  .map(value)
}

///|
fn selected_value(root : @dom.Element, name : String) -> String? {
  guard descendants(root, e => {
      e.tag == "select" && e.attributes.get("name") == Some(name)
    })
    .iter()
    .find_first(_ => true)
    is Some(select) else {
    return None
  }
  let options = descendants(select, e => e.tag == "option")
  match
    options.iter().find_first(option => option.attributes.contains("selected")) {
    Some(option) => Some(value(option))
    None => options.iter().find_first(_ => true).map(value)
  }
}

///|
fn after_label(source : String, label : String) -> String {
  match source.split(label).to_array() {
    [_, rest, ..] => rest.trim().to_owned()
    _ => ""
  }
}

///|
fn decimal(source : String) -> Int {
  source.fold(init=0, (n, c) => {
    if c >= '0' && c <= '9' {
      n * 10 + c.to_int() - '0'.to_int()
    } else {
      n
    }
  })
}

///|
pub fn logged_out(html : String) -> Bool {
  let root = @dom.parse_document(html).root
  descendants(root, e => e.tag == "li" && has_class(e, "logout")).is_empty() &&
  descendants(root, e => e.tag == "a" && text(e) == "로그인").length() > 0
}

///|
pub fn extract_catalogue(
  html : String,
) -> @model.CatalogueSearch raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let total_nodes = descendants(root, e => {
    e.tag == "strong" && has_class(e, "themeFC")
  })
  guard total_nodes
    .iter()
    .find_first(e => text(e).has_suffix("건") || decimal(text(e)) > 0)
    is Some(total) else {
    raise @model.MissingField("catalogue total")
  }
  let rows = descendants(root, e => e.tag == "ul" && has_class(e, "resultList"))
    .iter()
    .flat_map(list => {
      list.children
      .iter()
      .filter_map(node => {
        if node is @dom.Element(e) && e.tag == "li" {
          Some(e)
        } else {
          None
        }
      })
    })
  let items = rows
    .iter()
    .filter_map(row => {
      guard descendants(row, e => e.tag == "dt" && has_class(e, "tit"))
        .iter()
        .find_first(_ => true)
        is Some(title_node) else {
        return None
      }
      guard (
          descendants(title_node, e => e.tag == "a")
          .iter()
          .find_first(_ => true),
          descendants(row, e => e.tag == "dd" && has_class(e, "author"))
          .iter()
          .find_first(_ => true),
        )
        is (Some(title_link), Some(author_node)) else {
        return None
      }
      let spans = descendants(author_node, e => e.tag == "span")
        .iter()
        .map(text)
        .to_array()
      let onclicks = descendants(row, e => e.attributes.contains("onclick")).map(e => {
          e.attributes["onclick"]
        },
      )
      let registration = onclicks
        .iter()
        .flat_map(s => s.split("'"))
        .find_first(s => s.has_prefix("FIC"))
        .map(s => s.to_owned())
        .unwrap_or("")
      let mut manage = ""
      for onclick in onclicks {
        let parts = onclick.split("'").to_array()
        for i, part in parts {
          if part == registration && i >= 2 {
            if parts[i - 2].length() == 2 {
              manage = parts[i - 2].to_owned()
            } else if i + 2 < parts.length() {
              manage = parts[i + 2].to_owned()
            }
          }
        }
      }
      let item : @model.CatalogueBook = {
        title: text(title_link),
        author: spans
        .iter()
        .find_first(s => s.has_prefix("저자"))
        .map(s => after_label(s, ":"))
        .unwrap_or(""),
        publisher: spans
        .iter()
        .find_first(s => s.has_prefix("발행자"))
        .map(s => after_label(s, ":"))
        .unwrap_or(""),
        publication_year: spans
        .iter()
        .find_first(s => s.has_prefix("발행연도"))
        .map(s => after_label(s, ":"))
        .unwrap_or(""),
        manage_code: manage,
        registration_number: registration,
      }
      Some(item)
    })
    .to_array()
  { total_count: decimal(text(total)), items }
}

///|
pub fn extract_current_loans(html : String) -> Array[@model.CurrentLoan] {
  let root = @dom.parse_document(html).root
  descendants(root, e => e.tag == "ul" && has_class(e, "article-list"))
  .iter()
  .flat_map(list => {
    list.children
    .iter()
    .filter_map(node => {
      if node is @dom.Element(e) && e.tag == "li" {
        Some(e)
      } else {
        None
      }
    })
  })
  .iter()
  .filter_map(row => {
    guard descendants(row, e => e.tag == "p" && has_class(e, "title"))
      .iter()
      .find_first(_ => true)
      is Some(title_node) else {
      return None
    }
    let spans = descendants(row, e => e.tag == "span")
      .iter()
      .map(text)
      .to_array()
    let loan : @model.CurrentLoan = {
      title: text(title_node),
      loan_date_text: spans
      .iter()
      .find_first(s => s.has_prefix("대출일"))
      .map(s => after_label(s, ":"))
      .unwrap_or(""),
      due_date_text: spans
      .iter()
      .find_first(s => s.has_prefix("반납예정일"))
      .map(s => after_label(s, ":"))
      .unwrap_or(""),
      return_status_text: spans
      .iter()
      .find_first(s => s.has_prefix("상태"))
      .map(s => after_label(s, ":"))
      .unwrap_or(""),
    }
    Some(loan)
  })
  .to_array()
}

///|
pub fn extract_hope_defaults(
  html : String,
) -> @model.HopeBookDefaults raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let fields : Map[String, String] = Map([])
  descendants(root, e => e.tag == "input" || e.tag == "textarea").each(e => {
    if e.attributes.get("name") is Some(name) {
      fields[name] = value(e)
    }
  })
  descendants(root, e => e.tag == "select").each(e => {
    if e.attributes.get("name") is Some(name) &&
      selected_value(root, name) is Some(v) {
      fields[name] = v
    }
  })
  let aliases = [
    ("mobileNo1", "mobile_no_1"),
    ("mobileNo2", "mobile_no_2"),
    ("mobileNo3", "mobile_no_3"),
    ("handPhone", "phone"),
  ]
  aliases.each(pair => {
    let (source, target) = pair
    if fields.get(source) is Some(v) {
      fields[target] = v
    }
  })
  let p1 = fields.get("mobileNo1").unwrap_or("")
  let p2 = fields.get("mobileNo2").unwrap_or("")
  let p3 = fields.get("mobileNo3").unwrap_or("")
  guard (fields.get("email"), p1 != "" && p2 != "" && p3 != "")
    is (Some(email), true) else {
    raise @model.MissingField("hope-book defaults")
  }
  let phone = "\{p1}-\{p2}-\{p3}"
  fields["handPhone"] = phone
  fields["phone"] = phone
  { email, mobile_no_1: p1, mobile_no_2: p2, mobile_no_3: p3, phone, fields }
}

///|
pub fn extract_interloan_preparation(
  html : String,
) -> @model.InterloanPreparation raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  guard (
      field(root, "manageCode"),
      field(root, "regNo"),
      field(root, "giveLibCode"),
      field(root, "appendixApplyYn"),
      selected_value(root, "userKey"),
    )
    is (
      Some(manage),
      Some(registration),
      Some(providing),
      Some(appendix),
      Some(user_key),
    ) else {
    raise @model.MissingField("interloan preparation")
  }
  {
    manage_code: manage,
    registration_number: registration,
    providing_library_code: providing,
    appendix_apply: appendix,
    user_key,
  }
}

///|
pub fn classify_submission(html : String) -> @model.SubmissionEvidence {
  let message = text(@dom.parse_document(html).root)
  if html.contains("신청이 완료 되었습니다.") ||
    html.contains("상호대차 신청이 완료되었습니다.") {
    Submitted(if message == "" { "submitted" } else { message })
  } else if descendants(@dom.parse_document(html).root, e => {
      has_class(e, "messageBox")
    })
    .iter()
    .find_first(_ => true)
    is Some(box) {
    SubmitFailed(text(box))
  } else {
    SubmitFailed("unknown response")
  }
}
