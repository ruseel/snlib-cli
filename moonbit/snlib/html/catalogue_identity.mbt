///|
/// Interpret only callbacks whose registration/manage argument positions are known.
fn callback_identity(onclick : String) -> (String, String)? {
  guard onclick.split_once("(") is Some((name, rest)) else { return None }
  let name = name.trim().to_owned().replace(old="javascript:", new="")
  let delimiter = if rest.contains("'") { "'" } else { "\"" }
  let parts = rest.split(delimiter).to_array()
  let args : Array[String] = []
  for i, part in parts {
    if i % 2 == 1 {
      args.push(part.trim().to_owned())
    }
  }
  let identity = match (name, args) {
    ("fnBandLillApplyPop" | "fnDooraeApplyPopLogin", [manage, registration, ..]) =>
      Some((manage, registration))
    ("fnLoanReservationApplyProc", [_, registration, manage, ..]) =>
      Some((manage, registration))
    _ => None
  }
  guard identity is Some((manage, registration)) else { return None }
  let manage = @model.normalize_manage_code(manage)
  guard @model.valid_manage_code(manage) && registration != "" else {
    return None
  }
  Some((manage, registration))
}

///|
fn catalogue_identity(row : @dom.Element) -> (String, String) {
  for element in descendants(row, e => e.attributes.contains("onclick")) {
    if callback_identity(element.attributes["onclick"]) is Some(identity) {
      return identity
    }
  }
  // A book without an actionable callback may still display its registration.
  let registration = descendants(row, e => e.tag == "span")
    .iter()
    .map(text)
    .find_first(s => s.has_prefix("등록번호:"))
    .map(s => after_label(s, ":"))
    .unwrap_or("")
  ("", registration)
}
