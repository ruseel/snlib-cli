///|
pub(all) suberror CodeError {
  InvalidManageCode(String)
  InvalidLibraryCode(String)
} derive(Eq, Debug)

///|
pub fn normalize_manage_code(value : String) -> String {
  value.trim().to_upper().to_owned()
}

///|
pub fn valid_manage_code(value : String) -> Bool {
  value.length() == 2 &&
  value[0] >= 'A' &&
  value[0] <= 'Z' &&
  value[1] >= 'A' &&
  value[1] <= 'Z'
}

///|
pub fn manage_code(value : String) -> String raise CodeError {
  let normalized = normalize_manage_code(value)
  if valid_manage_code(normalized) {
    normalized
  } else {
    raise InvalidManageCode(value)
  }
}

///|
pub fn normalize_library_code(value : String) -> String {
  value.trim().to_owned()
}

///|
pub fn valid_library_code(value : String) -> Bool {
  if value.length() != 6 {
    return false
  }
  for unit in value {
    if unit < '0' || unit > '9' {
      return false
    }
  }
  true
}

///|
pub fn library_code(value : String) -> String raise CodeError {
  let normalized = normalize_library_code(value)
  if valid_library_code(normalized) {
    normalized
  } else {
    raise InvalidLibraryCode(value)
  }
}
