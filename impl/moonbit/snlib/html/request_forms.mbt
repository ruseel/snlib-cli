///|
pub fn extract_hope_form(
  html : String,
) -> Map[String, String] raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  guard descendants(root, e => {
      e.tag == "form" &&
      (
        e.attributes.get("id") == Some("registForm") ||
        e.attributes.get("name") == Some("registForm") ||
        (field(e, "title") is Some(_) && field(e, "author") is Some(_))
      )
    })
    .iter()
    .find_first(_ => true)
    is Some(form) else {
    raise @model.MissingField("hope-book form")
  }
  let fields : Map[String, String] = Map([])
  for
    element in descendants(form, e => {
      ["input", "textarea", "select"].contains(e.tag)
    }) {
    guard element.attributes.get("name") is Some(name) &&
      name != "" &&
      !element.attributes.contains("disabled") else {
      continue
    }
    let kind = element.attributes.get("type").unwrap_or("").to_lower()
    if ["checkbox", "radio"].contains(kind) &&
      !element.attributes.contains("checked") {
      continue
    }
    fields[name] = if element.tag == "select" {
      selected_value(form, name).unwrap_or("")
    } else if element.tag == "textarea" {
      text(element)
    } else {
      value(element)
    }
  }
  fields
}

///|
pub fn extract_interloan_form(
  html : String,
) -> Map[String, String] raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let fields : Map[String, String] = Map([])
  for
    name in ["manageCode", "regNo", "giveLibCode", "appendixApplyYn", "userKey"] {
    let result = if name == "userKey" {
      match field(root, name) {
        Some(v) if v != "" => Some(v)
        _ => selected_value(root, name)
      }
    } else {
      field(root, name)
    }
    if result is Some(value) {
      fields[name] = value
    }
  }
  guard fields.contains("manageCode") && fields.contains("regNo") else {
    raise @model.MissingField("interloan form")
  }
  fields
}
