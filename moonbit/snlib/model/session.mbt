///|
pub(all) enum SessionStatus {
  Active
  Expired
  Rejected
} derive(Eq, Debug)

///|
pub(all) struct Session {
  patron_id : String
  authenticated_at_ms : UInt64
  remembered : Bool
  status : SessionStatus
} derive(Eq, Debug)

///|
pub let session_duration_ms : UInt64 = 3UL * 60UL * 60UL * 1000UL

///|
pub fn Session::is_usable(self : Session, now_ms : UInt64) -> Bool {
  self.remembered &&
  self.status == Active &&
  now_ms < self.authenticated_at_ms + session_duration_ms
}
