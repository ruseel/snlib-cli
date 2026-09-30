///|
pub let login_page_path : String = "/intro/memberLogin.do"

///|
pub let login_submit_path : String = "/intro/menu/10068/program/30025/memberLoginProc.do"

///|
pub let login_verify_path : String = "/intro/menu/10060/program/30019/mypage/loanStatusList.do"

///|
pub let my_info_path : String = "/intro/menu/10055/program/30017/mypage/myInfo.do"

///|
pub(all) enum LoginStatus {
  LoginSuccess
  LoginRejected
  LoginFailure(RemoteError)
} derive(Eq, Debug)

///|
pub(all) enum AccountResult {
  AccountInfo(@model.MyInfoData)
  AccountLoggedOut
  AccountFailure(RemoteError)
} derive(Eq, Debug)

///|
fn account_request(
  path : String,
  verb : HttpMethod,
  referer : String,
) -> HttpRequest {
  {
    verb,
    base_url: default_base_url,
    path,
    query: {},
    headers: {
      "User-Agent": "Mozilla/5.0 snlib-lib",
      "Referer": default_base_url + referer,
    },
  }
}

///|
pub fn login(
  input : @model.LoginInput,
  transport : (HttpRequest) -> HttpResponse raise TransportError,
) -> LoginStatus {
  let page = transport(account_request(login_page_path, Get, login_page_path)) catch {
    Failed(_) =>
      return LoginFailure(Network("HTTP request failed during login."))
  }
  if page.status < 200 || page.status >= 300 {
    return LoginFailure(HttpStatus(page.status))
  }
  let submitted = transport(
    account_request(
      login_submit_path,
      Post({
        "returnUrl": "aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw==",
        "userId": input.user_id,
        "password": input.password,
      }),
      login_page_path,
    ),
  ) catch {
    Failed(_) =>
      return LoginFailure(Network("HTTP request failed during login."))
  }
  if submitted.status == 401 || submitted.status == 403 {
    return LoginRejected
  }
  // POST redirects are not followed: credentials must never be replayed to a
  // redirected origin. Authentication is checked using a separate, fixed GET.
  if submitted.status < 200 || submitted.status >= 400 {
    return LoginFailure(HttpStatus(submitted.status))
  }
  let verified = transport(
    account_request(login_verify_path, Get, login_page_path),
  ) catch {
    Failed(_) =>
      return LoginFailure(Network("HTTP request failed during login."))
  }
  if verified.status == 401 || verified.status == 403 {
    return LoginRejected
  }
  if verified.status < 200 || verified.status >= 300 {
    return LoginFailure(HttpStatus(verified.status))
  }
  if @html.logged_out(verified.body) {
    return LoginRejected
  }
  if !@html.authenticated_page(verified.body) {
    return LoginFailure(
      Parse("Authentication could not be confirmed from the verification page."),
    )
  }
  LoginSuccess
}

///|
pub fn my_info(
  transport : (HttpRequest) -> HttpResponse raise TransportError,
) -> AccountResult {
  let response = transport(
    account_request(my_info_path, Get, "/intro/main/index.do"),
  ) catch {
    Failed(_) =>
      return AccountFailure(
        Network("HTTP request failed while retrieving account information."),
      )
  }
  if response.status == 401 || response.status == 403 {
    return AccountLoggedOut
  }
  if response.status < 200 || response.status >= 300 {
    return AccountFailure(HttpStatus(response.status))
  }
  if @html.logged_out(response.body) {
    return AccountLoggedOut
  }
  let info = @html.extract_my_info(response.body) catch {
    _ =>
      return AccountFailure(
        Parse("The response does not contain valid account information."),
      )
  }
  AccountInfo(info)
}
