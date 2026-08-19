#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "moonbit.h"

static char *snlib_copy_path(moonbit_bytes_t bytes, int32_t len) {
  char *result = malloc((size_t)len + 1);
  if (result == NULL) return NULL;
  memcpy(result, bytes, (size_t)len);
  result[len] = '\0';
  return result;
}

static int snlib_mkdir_parents(char *path) {
  for (char *p = path + 1; *p; p++) {
    if (*p != '/') continue;
    *p = '\0';
    if (mkdir(path, 0700) == 0) {
      if (chmod(path, 0700) != 0) return -1;
    } else if (errno != EEXIST) {
      return -1;
    }
    *p = '/';
  }
  return 0;
}

MOONBIT_FFI_EXPORT int32_t snlib_store_secure_write(
    moonbit_bytes_t path_bytes, int32_t path_len,
    moonbit_bytes_t content, int32_t content_len) {
  char *path = snlib_copy_path(path_bytes, path_len);
  if (path == NULL) return -1;
  if (snlib_mkdir_parents(path) != 0) { free(path); return -1; }
  char *temporary = malloc((size_t)path_len + 12);
  if (temporary == NULL) { free(path); return -1; }
  snprintf(temporary, (size_t)path_len + 12, "%s.tmp.XXXXXX", path);
  int fd = mkstemp(temporary);
  if (fd < 0 || fchmod(fd, 0600) != 0) goto fail;
  int32_t written = 0;
  while (written < content_len) {
    ssize_t amount = write(fd, content + written, (size_t)(content_len - written));
    if (amount <= 0) goto fail;
    written += (int32_t)amount;
  }
  if (fsync(fd) != 0 || close(fd) != 0) { fd = -1; goto fail; }
  fd = -1;
  if (rename(temporary, path) != 0) goto fail;
  free(temporary);
  free(path);
  return 0;
fail:
  if (fd >= 0) close(fd);
  unlink(temporary);
  free(temporary);
  free(path);
  return -1;
}

MOONBIT_FFI_EXPORT int32_t snlib_store_file_mode(
    moonbit_bytes_t path_bytes, int32_t path_len) {
  char *path = snlib_copy_path(path_bytes, path_len);
  if (path == NULL) return -1;
  struct stat info;
  int result = stat(path, &info);
  free(path);
  return result == 0 ? (int32_t)(info.st_mode & 0777) : -1;
}
