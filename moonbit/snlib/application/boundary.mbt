///|
pub fn search_books(
  input : @model.SearchBooksInput,
  transport : (@remote.HttpRequest) -> @remote.HttpResponse raise @remote.TransportError,
) -> @model.CommandResult {
  match @remote.search_books(input, transport) {
    Success(catalogue) =>
      @model.command_result(
        Ok,
        "search-ok",
        "Catalogue search completed.",
        data=SearchBooksResult({
          items: catalogue.items,
          page: input.page,
          total_count: catalogue.total_count,
        }),
      )
    Failure(Network(message)) =>
      @model.command_result(RemoteError, "http-request-failed", message)
    Failure(HttpStatus(status)) =>
      @model.command_result(
        RemoteError,
        "search-request-failed",
        "Search request failed with status \{status}.",
      )
    Failure(Parse(message)) =>
      @model.command_result(
        RemoteError,
        "search-response-parse-failed",
        message,
      )
  }
}
