#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
  printf '%s\n' 'Usage: snlib-cli.sh <command>; supply one JSON object on stdin, not CLI flags.' >&2
  exit 2
fi
command="${1:---help}"
if [[ "$command" != "help" && "$command" != "--help" && -t 0 ]]; then
  printf '%s\n' 'Supply one JSON object on stdin (use {} for commands without fields).' >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="${SNLIB_MOONBIT_DIR:-$script_dir/../../../moonbit}"
if [[ ! -f "$project_dir/moon.mod" || ! -f "$project_dir/cmd/snlib-cli/main.mbt" ]]; then
  printf '%s\n' 'MoonBit source not found. Set SNLIB_MOONBIT_DIR to the absolute path of a snlib-cli checkout’s moonbit/ directory.' >&2
  exit 1
fi
if ! command -v moon >/dev/null 2>&1; then
  printf '%s\n' 'MoonBit CLI not found. Install moon and ensure it is on PATH.' >&2
  exit 1
fi

# Resolve the project independently of the caller's working directory.
exec moon -C "$project_dir" run --target native cmd/snlib-cli -- "$command"
