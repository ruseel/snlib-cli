#!/usr/bin/env bash
# Package the existing host-native build; never compiles or uploads anything.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
case "$(uname -s)" in
  Darwin) os=darwin ;;
  Linux) os=linux ;;
  *) echo "Unsupported operating system" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64) arch=amd64 ;;
  *) echo "Unsupported architecture" >&2; exit 1 ;;
esac
[[ "$os-$arch" != "darwin-amd64" ]] || { echo "Intel macOS is unsupported" >&2; exit 1; }
binary="$repo_root/impl/moonbit/_build/native/debug/build/cmd/snlib-cli/snlib-cli.exe"
if [[ ! -x "$binary" ]]; then
  echo "Build first: moon -C impl/moonbit build --target native" >&2
  exit 1
fi
# Reject a build that cannot execute on this host. No network or account access.
"$binary" --help </dev/null >/dev/null
output="${1:-$repo_root/target/native-release}"
asset="snlib-cli-$os-$arch"
mkdir -p "$output"
cp "$binary" "$output/$asset"
chmod 755 "$output/$asset"
(
  cd "$output"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$asset" > "$asset.sha256"
  else
    shasum -a 256 "$asset" > "$asset.sha256"
  fi
)
echo "Packaged $output/$asset"
