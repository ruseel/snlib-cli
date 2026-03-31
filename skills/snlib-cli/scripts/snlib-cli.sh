#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/snlib-cli.jar"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "ERROR: missing $JAR_PATH" >&2
  echo "Run: bb scripts/clawhub-release.bb prepare --version <version>" >&2
  exit 1
fi

exec clojure \
  -Srepro \
  -Sdeps "{:deps {io.github.ruseel/snlib-cli {:local/root \"$JAR_PATH\"}}}" \
  -M -m snlib.cli "$@"
