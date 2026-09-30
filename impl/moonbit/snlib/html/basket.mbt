///|
pub(all) struct BasketGroup {
  key : String?
  name : String?
  count : Int?
}

///|
pub fn extract_basket_group(
  html : String,
) -> BasketGroup raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let heading = descendants(root, e => has_class(e, "htitle"))
    .iter()
    .find_first(_ => true)
  let mut key = action_key(root, "fnBasketGroupBookMore")
  if key is None &&
    html.split_once("fnBasketGroupBookMore(") is Some((_, rest)) &&
    rest.split_once(")") is Some((arg, _)) {
    let arg = arg.trim().to_owned()
    if arg != "" && arg.iter().all(c => c >= '0' && c <= '9') {
      key = Some(arg)
    }
  }
  guard heading is Some(_) ||
    html.contains("wishBookList") ||
    html.contains("basketGroup") ||
    html.contains("emptyNote") else {
    raise @model.MissingField("basket group")
  }
  let name = heading.map(e => {
    e.children
    .iter()
    .filter_map(node => {
      match node {
        @dom.Text(value) => Some(value)
        _ => None
      }
    })
    .to_array()
    .join("")
    .trim()
    .to_owned()
  })
  let count = match heading {
    Some(e) =>
      descendants(e, child => has_class(child, "normal"))
      .iter()
      .find_first(_ => true)
      .map(child => decimal(text(child)))
    None => None
  }
  { key, name, count }
}

///|
pub fn extract_basket_books(
  html : String,
) -> Array[@model.BasketBook] raise @model.ExtractionError {
  let root = @dom.parse_document(html).root
  let lists = descendants(root, e => {
    has_class(e, "listWrap") || has_class(e, "article-list")
  })
  guard !lists.is_empty() else { raise @model.MissingField("basket list") }
  lists
  .iter()
  .flat_map(list => {
    list.children
    .iter()
    .filter_map(node => {
      match node {
        @dom.Element(row) if row.tag == "li" &&
          descendants(row, e => has_class(e, "emptyNote")).is_empty() => {
          let fields = row_fields(row)
          fn class_text(name : String) -> String? {
            descendants(row, e => has_class(e, name))
            .iter()
            .find_first(_ => true)
            .map(text)
          }
          let book : @model.BasketBook = {
            title: class_text("book_name").unwrap_or(row_title(row)),
            author: class_text("bk_writer").unwrap_or(
              fields.get("저자").unwrap_or(""),
            ),
            publisher: class_text("bk_publish").unwrap_or(
              fields.get("출판사").unwrap_or(""),
            ),
          }
          Some(book)
        }
        _ => None
      }
    })
  })
  .to_array()
}
