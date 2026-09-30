///|
test "session TTL rejects future times and expires at the exact boundary" {
  let session : @model.Session = {
    patron_id: "fictional",
    authenticated_at_ms: 1000UL,
    remembered: true,
    status: Active,
  }
  assert_false(session.is_usable(999UL))
  assert_true(session.is_usable(1000UL))
  assert_true(session.is_usable(1000UL + @model.session_duration_ms - 1UL))
  assert_false(session.is_usable(1000UL + @model.session_duration_ms))
}

///|
test "session TTL does not overflow near UInt64 maximum" {
  let maximum = 18446744073709551615UL
  let session : @model.Session = {
    patron_id: "fictional",
    authenticated_at_ms: maximum - 1000UL,
    remembered: true,
    status: Active,
  }
  assert_true(session.is_usable(maximum))
  assert_false(session.is_usable(0UL))
}

///|
test "inactive and unremembered sessions remain unusable" {
  for status in [@model.Expired, @model.Rejected] {
    let session : @model.Session = {
      patron_id: "fictional",
      authenticated_at_ms: 1000UL,
      remembered: true,
      status,
    }
    assert_false(session.is_usable(1000UL))
  }
  let session : @model.Session = {
    patron_id: "fictional",
    authenticated_at_ms: 1000UL,
    remembered: false,
    status: Active,
  }
  assert_false(session.is_usable(1000UL))
}
