#include <curl/curl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "moonbit.h"

#define SNLIB_MAX_BODY (8 * 1024 * 1024)

struct snlib_body {
  unsigned char *data;
  size_t length;
  int too_large;
};

static moonbit_bytes_t snlib_packet(int status, const void *data, size_t length,
                                   const char *cookies, size_t cookie_length) {
  moonbit_bytes_t packet = moonbit_make_bytes_raw((int32_t)(length + cookie_length) + 8);
  for (int i = 0; i < 4; i++) {
    packet[i] = (unsigned char)((unsigned)status >> (8 * i));
    packet[4 + i] = (unsigned char)((unsigned)cookie_length >> (8 * i));
  }
  if (cookie_length != 0) memcpy(packet + 8, cookies, cookie_length);
  if (length != 0) memcpy(packet + 8 + cookie_length, data, length);
  return packet;
}

static moonbit_bytes_t snlib_error(const char *message) {
  return snlib_packet(0, message, strlen(message), NULL, 0);
}

static char *snlib_copy(moonbit_bytes_t bytes, int32_t length) {
  char *copy = malloc((size_t)length + 1);
  if (copy == NULL) return NULL;
  memcpy(copy, bytes, (size_t)length);
  copy[length] = '\0';
  return copy;
}

static size_t snlib_receive(char *data, size_t size, size_t count, void *context) {
  struct snlib_body *body = context;
  if (size != 0 && count > SIZE_MAX / size) return 0;
  size_t length = size * count;
  if (length > SNLIB_MAX_BODY - body->length) {
    body->too_large = 1;
    return 0;
  }
  if (length == 0) return 0;
  unsigned char *next = realloc(body->data, body->length + length);
  if (next == NULL) return 0;
  body->data = next;
  memcpy(body->data + body->length, data, length);
  body->length += length;
  return length;
}

MOONBIT_FFI_EXPORT moonbit_bytes_t snlib_http_request(
    moonbit_bytes_t url_bytes, int32_t url_length,
    moonbit_bytes_t header_bytes, int32_t header_length,
    moonbit_bytes_t form_bytes, int32_t form_length, int32_t is_post,
    moonbit_bytes_t cookie_bytes, int32_t cookie_length) {
  if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK)
    return snlib_error("Unable to initialize libcurl.");

  char *url = snlib_copy(url_bytes, url_length);
  char *header_block = snlib_copy(header_bytes, header_length);
  char *form = snlib_copy(form_bytes, form_length);
  char *cookie_block = snlib_copy(cookie_bytes, cookie_length);
  char *exported_cookies = NULL;
  struct curl_slist *cookie_list = NULL;
  CURL *curl = curl_easy_init();
  struct curl_slist *headers = NULL;
  struct snlib_body body = {0};
  char error[CURL_ERROR_SIZE] = {0};
  moonbit_bytes_t result;
  if (url == NULL || header_block == NULL || form == NULL || cookie_block == NULL || curl == NULL) {
    result = snlib_error("Unable to allocate HTTP request.");
    goto cleanup;
  }
  for (char *line = header_block; *line;) {
    char *end = strchr(line, '\n');
    if (end != NULL) *end = '\0';
    struct curl_slist *next = curl_slist_append(headers, line);
    if (next == NULL) {
      result = snlib_error("Unable to allocate HTTP headers.");
      goto cleanup;
    }
    headers = next;
    if (end == NULL) break;
    line = end + 1;
  }

#define SET_OPTION(option, value) do { \
  CURLcode code = curl_easy_setopt(curl, option, value); \
  if (code != CURLE_OK) { result = snlib_error(curl_easy_strerror(code)); goto cleanup; } \
} while (0)

  SET_OPTION(CURLOPT_URL, url);
  SET_OPTION(CURLOPT_HTTPHEADER, headers);
  SET_OPTION(CURLOPT_WRITEFUNCTION, snlib_receive);
  SET_OPTION(CURLOPT_WRITEDATA, &body);
  SET_OPTION(CURLOPT_ERRORBUFFER, error);
  SET_OPTION(CURLOPT_CONNECTTIMEOUT, 10L);
  SET_OPTION(CURLOPT_TIMEOUT, 30L);
  SET_OPTION(CURLOPT_NOSIGNAL, 1L);
  SET_OPTION(CURLOPT_FOLLOWLOCATION, is_post ? 0L : 1L);
  SET_OPTION(CURLOPT_MAXREDIRS, 5L);
  // Never disable TLS verification, including on redirected requests.
  SET_OPTION(CURLOPT_SSL_VERIFYPEER, 1L);
  SET_OPTION(CURLOPT_SSL_VERIFYHOST, 2L);
  SET_OPTION(CURLOPT_ACCEPT_ENCODING, "");
  SET_OPTION(CURLOPT_COOKIEFILE, ""); // In-memory cookie engine, never a disk jar.
  for (char *line = cookie_block; *line;) {
    char *end = strchr(line, '\n');
    if (end != NULL) *end = '\0';
    SET_OPTION(CURLOPT_COOKIELIST, line);
    if (end == NULL) break;
    line = end + 1;
  }
  if (is_post) {
    SET_OPTION(CURLOPT_POST, 1L);
    SET_OPTION(CURLOPT_POSTFIELDS, form);
    SET_OPTION(CURLOPT_POSTFIELDSIZE, (long)form_length);
  }
#if LIBCURL_VERSION_NUM >= 0x075500
  SET_OPTION(CURLOPT_PROTOCOLS_STR, "http,https");
  SET_OPTION(CURLOPT_REDIR_PROTOCOLS_STR, "https");
#else
  SET_OPTION(CURLOPT_PROTOCOLS, (long)(CURLPROTO_HTTP | CURLPROTO_HTTPS));
  SET_OPTION(CURLOPT_REDIR_PROTOCOLS, (long)CURLPROTO_HTTPS);
#endif
  // Local HTTP test servers may redirect to HTTP; HTTPS must not downgrade.
  if (strncmp(url, "http://", 7) == 0) {
#if LIBCURL_VERSION_NUM >= 0x075500
    SET_OPTION(CURLOPT_REDIR_PROTOCOLS_STR, "http,https");
#else
    SET_OPTION(CURLOPT_REDIR_PROTOCOLS, (long)(CURLPROTO_HTTP | CURLPROTO_HTTPS));
#endif
  }
  CURLcode code = curl_easy_perform(curl);
  if (code != CURLE_OK) {
    result = snlib_error(body.too_large ? "HTTP response exceeds 8 MiB limit."
                        : error[0] ? error : curl_easy_strerror(code));
    goto cleanup;
  }
  long status = 0;
  code = curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
  if (code != CURLE_OK || status < 100 || status > 599) {
    result = snlib_error("Invalid HTTP response status.");
    goto cleanup;
  }
  code = curl_easy_getinfo(curl, CURLINFO_COOKIELIST, &cookie_list);
  if (code != CURLE_OK) {
    result = snlib_error("Unable to export session cookies.");
    goto cleanup;
  }
  size_t exported_length = 0;
  for (struct curl_slist *line = cookie_list; line != NULL; line = line->next) {
    size_t length = strlen(line->data) + 1;
    if (length > 65536 - exported_length) {
      result = snlib_error("Session cookies exceed 64 KiB limit.");
      goto cleanup;
    }
    exported_length += length;
  }
  exported_cookies = malloc(exported_length + 1);
  if (exported_cookies == NULL) {
    result = snlib_error("Unable to allocate session cookies.");
    goto cleanup;
  }
  size_t offset = 0;
  for (struct curl_slist *line = cookie_list; line != NULL; line = line->next) {
    size_t length = strlen(line->data);
    memcpy(exported_cookies + offset, line->data, length);
    offset += length;
    exported_cookies[offset++] = '\n';
  }
  exported_cookies[offset] = '\0';
  result = snlib_packet((int)status, body.data, body.length, exported_cookies, exported_length);
cleanup:
  if (curl != NULL) curl_easy_cleanup(curl);
  curl_slist_free_all(headers);
  curl_slist_free_all(cookie_list);
  free(exported_cookies);
  free(cookie_block);
  free(form);
  free(body.data);
  free(header_block);
  free(url);
  curl_global_cleanup();
  return result;
}
