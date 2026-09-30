///|
test "catalogue identifiers are independent of anonymized fixture prefixes" {
  let source = @fs.read_file_to_string(
    "../../test/fixtures/snlib/search-books/interloan-target.html",
  )
  for prefix in ["CEM", "GEM", "ABC"] {
    let html = source.split("FIC").join(prefix)
    let result = @html.extract_catalogue(html)
    assert_eq(result.items.length(), 10)
    assert_eq(result.items[0].registration_number, "\{prefix}000000101")
    assert_eq(result.items[0].manage_code, "MG")
    assert_eq(result.items[1].registration_number, "\{prefix}000000102")
    assert_eq(result.items[1].manage_code, "MH")
    assert_eq(result.items[9].manage_code, "MB")
  }
}

///|
test "catalogue retains displayed registration when there is no known action" {
  let html =
    #|<strong class="themeFC">1건</strong><ul class="resultList"><li>
    #|<dt class="tit"><a>Book</a></dt><dd class="author"><span>저자: Fictional</span></dd>
    #|<span>등록번호: CEM000000001</span>
    #|<a onclick="unrelated('ZZ','WRONG')">Other action</a>
    #|</li></ul>
  let result = @html.extract_catalogue(html)
  assert_eq(result.items[0].registration_number, "CEM000000001")
  assert_eq(result.items[0].manage_code, "")
}

///|
test "catalogue handles double-quoted callback arguments" {
  let html =
    #|<strong class="themeFC">1건</strong><ul class="resultList"><li>
    #|<dt class="tit"><a>Book</a></dt><dd class="author"><span>저자: Fictional</span></dd>
    #|<a onclick='javascript:fnBandLillApplyPop("MA","CEM000000001"); return false;'>Apply</a>
    #|</li></ul>
  let result = @html.extract_catalogue(html)
  assert_eq(result.items[0].registration_number, "CEM000000001")
  assert_eq(result.items[0].manage_code, "MA")
}
