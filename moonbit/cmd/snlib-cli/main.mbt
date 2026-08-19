///|
extern "C" fn getchar() -> Int = "getchar"

///|
fn read_stdin() -> String {
  let bytes : Array[Byte] = []
  for ;; {
    let value = getchar()
    if value < 0 {
      break
    }
    bytes.push(value.to_byte())
  }
  @utf8.decode_lossy(Bytes::from_array(bytes))
}

///|
fn main {
  let args = @env.args()
  let command = if args.length() > 1 { args[1] } else { "help" }
  let input = if command == "help" || command == "--help" {
    "{}"
  } else {
    read_stdin()
  }
  let session = @store.load_session()
  println(@cli.render(@cli.run(command, input, session, @env.now())))
}
