#!/usr/bin/env bash
set -euo pipefail

exec clojure \
  -Srepro \
  -Sdeps "{:deps {io.github.ruseel/snlib-cli {:local/root \"scripts/snlib-cli.jar\"}}}" \
  -M -m snlib.cli "$@"
