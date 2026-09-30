///|
pub let default_base_url : String = "https://snlib.go.kr"

///|
pub let search_path : String = "/intro/menu/10041/program/30009/plusSearchResultList.do"

///|
pub(all) enum HttpMethod {
  Get
  Post(Map[String, String])
} derive(Eq, Debug)

///|
pub(all) struct HttpRequest {
  verb : HttpMethod
  base_url : String
  path : String
  query : Map[String, Array[String]]
  headers : Map[String, String]
} derive(Eq, Debug)

///|
pub(all) struct HttpResponse {
  status : Int
  body : String
} derive(Eq, Debug)

///|
pub(all) suberror TransportError {
  Failed(String)
} derive(Eq, Debug)

///|
pub(all) enum RemoteError {
  Network(String)
  HttpStatus(Int)
  Parse(String)
} derive(Eq, Debug)

///|
pub(all) enum SearchResult {
  Success(@model.CatalogueSearch)
  Failure(RemoteError)
} derive(Eq, Debug)

///|
pub fn build_search_request(input : @model.SearchBooksInput) -> HttpRequest {
  let query : Map[String, Array[String]] = {
    "searchType": ["SIMPLE"],
    "searchMenuCollectionCategory": [""],
    "searchMenuEBookCategory": [""],
    "searchCategory": ["BOOK"],
    "searchKey": ["ALL"],
    "searchKey1": [""],
    "searchKey2": [""],
    "searchKey3": [""],
    "searchKey4": [""],
    "searchKey5": [""],
    "searchKeyword": [input.keyword],
    "searchKeyword1": [""],
    "searchKeyword2": [""],
    "searchKeyword3": [""],
    "searchKeyword4": [""],
    "searchKeyword5": [""],
    "searchOperator1": [""],
    "searchOperator2": [""],
    "searchOperator3": [""],
    "searchOperator4": [""],
    "searchOperator5": [""],
    "searchPublishStartYear": [""],
    "searchPublishEndYear": [""],
    "searchRoom": [""],
    "searchKdc": [""],
    "searchIsbn": [""],
    "currentPageNo": [input.page.to_string()],
    "viewStatus": ["IMAGE"],
    "preSearchKey": ["ALL"],
    "preSearchKeyword": [input.keyword],
    "searchSort": [input.sort],
    "searchOrder": [input.order],
    "searchRecordCount": [input.per_page.to_string()],
    "searchBookClass": ["ALL"],
    "searchSmLibrary": [""],
  }
  if input.manage_codes.is_empty() {
    query["searchPbLibrary"] = ["ALL"]
  } else {
    query["searchPbLibrary"] = [""]
    query["searchLibraryArr"] = input.manage_codes.copy()
  }
  {
    verb: Get,
    base_url: default_base_url,
    path: search_path,
    query,
    headers: {
      "User-Agent": "Mozilla/5.0 snlib-lib",
      "Referer": "https://snlib.go.kr/intro/main/index.do",
    },
  }
}

///|
pub fn search_books(
  input : @model.SearchBooksInput,
  transport : (HttpRequest) -> HttpResponse raise TransportError,
) -> SearchResult {
  let response = transport(build_search_request(input)) catch {
    Failed(message) => return Failure(Network(message))
  }
  if response.status < 200 || response.status >= 300 {
    return Failure(HttpStatus(response.status))
  }
  let catalogue = @html.extract_catalogue(response.body) catch {
    @model.InvalidHtml(message) => return Failure(Parse(message))
    @model.MissingField(field) =>
      return Failure(Parse("Missing field: \{field}."))
    @model.SubmitFailed(message) => return Failure(Parse(message))
  }
  Success(catalogue)
}
