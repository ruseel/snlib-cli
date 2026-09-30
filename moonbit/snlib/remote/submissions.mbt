///|
pub let interloan_popup_path : String = "/intro/doorae/bandLillApplyPop.do"

///|
pub let interloan_submit_path : String = "/intro/doorae/bandLillApplyPopProc.do"

///|
pub let hope_page_path : String = "/intro/menu/10045/program/30011/hopeBookApply.do"

///|
pub let hope_submit_path : String = "/intro/menu/10045/program/30011/hopeBookApplyProc.do"

///|
fn canonical_field(name : String) -> String {
  let key = name.trim().to_lower().split("_").join("-")
  match key {
    "manage-code" | "managecode" => "manageCode"
    "ea-isbn" | "eaisbn" => "eaIsbn"
    "publish-year" | "publishyear" => "publishYear"
    "hand-phone" | "handphone" | "mobile-no" | "mobileno" => "handPhone"
    "mobile-no1" | "mobileno1" => "mobileNo1"
    "mobile-no2" | "mobileno2" => "mobileNo2"
    "mobile-no3" | "mobileno3" => "mobileNo3"
    "sms-receipt-yn" | "smsreceiptyn" => "smsReceiptYn"
    "recom-opinion" | "recomopinion" => "recomOpinion"
    _ => name
  }
}

///|
fn phone_fields(
  payload : Map[String, String],
  override_phone : Bool,
) -> Unit raise CommandRemoteError {
  let phone = payload.get("handPhone").unwrap_or("")
  let digits = phone
    .iter()
    .filter(c => c >= '0' && c <= '9')
    .map(c => c.to_string())
    .to_array()
    .join("")
  if override_phone &&
    (
      (digits.length() != 10 && digits.length() != 11) ||
      !phone.iter().all(c => (c >= '0' && c <= '9') || c == '-' || c == ' ')
    ) {
    raise InvalidPayload("Invalid explicit phone number.")
  }
  if (override_phone || payload.get("mobileNo2").unwrap_or("") == "") &&
    (digits.length() == 10 || digits.length() == 11) {
    let end_middle = digits.length() - 4
    payload["mobileNo1"] = digits[0:3].to_owned()
    payload["mobileNo2"] = digits[3:end_middle].to_owned()
    payload["mobileNo3"] = digits[end_middle:].to_owned()
  }
  let pieces = ["mobileNo1", "mobileNo2", "mobileNo3"].map(name => {
    payload.get(name).unwrap_or("")
  })
  if pieces.all(s => s != "") {
    payload["handPhone"] = pieces.join("-")
  }
}

///|
fn validate_hope(
  payload : Map[String, String],
) -> Unit raise CommandRemoteError {
  for name in ["manageCode", "title", "author", "publisher", "email"] {
    if payload.get(name).unwrap_or("").trim() == "" {
      raise InvalidPayload("Missing required hope-book field: " + name)
    }
  }
  if !@model.valid_manage_code(payload["manageCode"]) {
    raise InvalidPayload("Invalid hope-book manage code.")
  }
  let email = payload["email"]
  guard email.split("@").to_array() is [local_part, domain] &&
    local_part != "" &&
    domain.contains(".") &&
    !domain.has_prefix(".") &&
    !domain.has_suffix(".") &&
    !domain.contains("..") &&
    !email.iter().any(c => c == ' ' || c == '\t' || c == '\n' || c == '\r') else {
    raise InvalidPayload("Invalid email address.")
  }
  if payload.get("smsReceiptYn") != Some("Y") {
    raise InvalidPayload(
      "Hope-book submission requires explicit smsReceiptYn=Y consent.",
    )
  }
  let phone = payload.get("handPhone").unwrap_or("").split("-").to_array()
  guard phone is [first, middle, last] &&
    first.length() == 3 &&
    (middle.length() == 3 || middle.length() == 4) &&
    last.length() == 4 &&
    phone.all(part => part.iter().all(c => c >= '0' && c <= '9')) else {
    raise InvalidPayload("Invalid phone number.")
  }
  let price = payload.get("price").unwrap_or("")
  if !price.iter().all(c => c >= '0' && c <= '9') {
    raise InvalidPayload("Price must contain digits only.")
  }
}

///|
pub fn submit_request(
  input : @model.CommandInput,
  transport : (HttpRequest) -> HttpResponse raise TransportError,
) -> @model.SubmissionData raise CommandRemoteError {
  let (payload, page_path, submit_path, submit, success_token) = match input {
    InterloanRequest(options) => {
      let request = account_request(
        interloan_popup_path,
        Get,
        "/intro/main/index.do",
      )
      request.query["manageCode"] = [options.manage_code]
      request.query["regNo"] = [options.reg_no]
      let body = checked_request(request, transport)
      let payload = parsed(body, @html.extract_interloan_form)
      if payload["manageCode"] != options.manage_code ||
        payload["regNo"] != options.reg_no {
        raise RequestFailure(
          Parse("Interloan form identity does not match the requested book."),
        )
      }
      payload["manageCode"] = options.manage_code
      payload["regNo"] = options.reg_no
      payload["aplLibCode"] = options.apl_lib_code
      payload["appendixApplyYn"] = options.appendix_apply_yn
      if options.give_lib_code is Some(code) {
        payload["giveLibCode"] = @model.normalize_library_code(code)
      }
      if options.user_key is Some(key) {
        payload["userKey"] = key
      }
      if options.submit {
        if !@model.valid_library_code(payload.get("giveLibCode").unwrap_or("")) ||
          payload.get("userKey").unwrap_or("").trim() == "" {
          raise InvalidPayload(
            "Interloan preparation is missing a valid giving library or user key.",
          )
        }
      }
      (
        payload,
        interloan_popup_path,
        interloan_submit_path,
        options.submit,
        "상호대차 신청이 완료되었습니다.",
      )
    }
    HopeBookRequest(options) => {
      let body = checked_request(
        account_request(hope_page_path, Get, "/intro/main/index.do"),
        transport,
      )
      let payload = parsed(body, @html.extract_hope_form)
      let mut override_phone = false
      for key, value in options.request {
        let key = canonical_field(key)
        if key == "handPhone" {
          override_phone = true
        }
        if key == "manageCode" &&
          !@model.valid_manage_code(@model.normalize_manage_code(value)) {
          raise InvalidPayload("Invalid request manage code.")
        }
        payload[key] = value
      }
      if options.manage_code is Some(code) {
        payload["manageCode"] = @model.normalize_manage_code(code)
      }
      if payload.get("manageCode") is Some(code) {
        payload["manageCode"] = @model.normalize_manage_code(code)
      }
      phone_fields(payload, override_phone)
      if options.submit {
        validate_hope(payload)
      }
      (
        payload,
        hope_page_path,
        hope_submit_path,
        options.submit,
        "신청이 완료 되었습니다.",
      )
    }
    _ => raise InvalidPayload("Not a request command.")
  }
  if !submit {
    return {
      prepared_payload: payload,
      submit_attempted: false,
      result_message: None,
    }
  }
  let request = account_request(submit_path, Post(payload), page_path)
  request.headers["Origin"] = default_base_url
  let body = checked_request(request, transport)
  // A 200 without explicit, command-specific evidence is never success.
  if !body.contains(success_token) {
    let message = match @html.classify_submission(body) {
      Submitted(_) => "The response did not confirm this submission."
      SubmitFailed(message) => message
    }
    raise SubmissionRejected(message)
  }
  {
    prepared_payload: payload,
    submit_attempted: true,
    result_message: Some(success_token),
  }
}
