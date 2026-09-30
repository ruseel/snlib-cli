/* Compile the real store with injected syscall failure, without changing production code. */
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

static int synced_fd = -1;
static int close_calls = 0;

static int failing_fsync(int fd) {
  synced_fd = fd;
  errno = EIO;
  return -1;
}

static int tracked_close(int fd) {
  close_calls++;
  return close(fd);
}

#define fsync failing_fsync
#define close tracked_close
#include "moonbit/snlib/store/store_native.c"
#undef fsync
#undef close

int main(int argc, char **argv) {
  assert(argc == 2);
  const char *content = "replacement";
  int result = snlib_store_secure_write(
      (moonbit_bytes_t)argv[1], (int32_t)strlen(argv[1]),
      (moonbit_bytes_t)content, (int32_t)strlen(content));
  assert(result == -1);
  assert(synced_fd >= 0);
  assert(close_calls == 1);
  errno = 0;
  assert(fcntl(synced_fd, F_GETFD) == -1);
  assert(errno == EBADF);
  return 0;
}
