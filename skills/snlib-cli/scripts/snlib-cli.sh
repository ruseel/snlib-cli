#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/snlib-cli.jar"
DEPS_FILE="$SCRIPT_DIR/deps.edn"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "ERROR: missing $JAR_PATH" >&2
  echo "Run: bb scripts/clawhub-release.bb prepare --version <version>" >&2
  exit 1
fi

if [[ ! -f "$DEPS_FILE" ]]; then
  echo "ERROR: missing $DEPS_FILE" >&2
  echo "Run: bb scripts/clawhub-release.bb prepare --version <version>" >&2
  exit 1
fi

RUNTIME_DEPS="$({ SNLIB_SCRIPT_DIR="$SCRIPT_DIR" clojure -Srepro -M -e '
(let [script-dir (System/getenv "SNLIB_SCRIPT_DIR")
      runtime-deps (-> (str script-dir "/deps.edn")
                       slurp
                       clojure.edn/read-string
                       (assoc :paths [(str script-dir "/snlib-cli.jar")]))]
  (print (pr-str runtime-deps)))
'; } )"

exec clojure \
  -Srepro \
  -Sdeps "$RUNTIME_DEPS" \
  -M -m snlib.cli "$@"
