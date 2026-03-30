#!/usr/bin/env bash
set -euo pipefail

# NOTE: This default repo URL still points at a maintainer-specific SSH remote.
# Replace it with a public clone URL before publishing to ClawHub.
SNLIB_GITHUB_REPO="${SNLIB_GITHUB_REPO:-https://github.com/ruseel/snlib-cli.git}"
# This launcher intentionally fetches source via tools.deps instead of bundling
# compiled artifacts into the skill. That keeps the published skill small and
# reviewable, but the maintainer must repin these values for each release.
SNLIB_GIT_TAG="${SNLIB_GIT_TAG:-20260330}"
SNLIB_GIT_SHA="${SNLIB_GIT_SHA:-9e88c1d8e9f5e806ef3319e3c1b393e26cfa7b48}"
# Optional complete -Sdeps override for dev/testing.
# Example: SNLIB_DEPS_EDN='{:deps {...}}'
SNLIB_DEPS_EDN="${SNLIB_DEPS_EDN:-}"

if [[ -n "${SNLIB_DEPS_EDN}" ]]; then
  exec clojure -Sdeps "${SNLIB_DEPS_EDN}" -M -m snlib.cli "$@"
fi

: "${SNLIB_GIT_TAG:?SNLIB_GIT_TAG must be set for tools.deps git deps.}"
: "${SNLIB_GIT_SHA:?SNLIB_GIT_SHA must be set for tools.deps git deps.}"

# Keep :git/url explicit because this repo name does not match io.github.ruseel/snlib-cli.
# Tradeoff: the published skill bundle is not self-contained; it shells out to
# Clojure and resolves this repo at runtime rather than shipping source/binaries.
exec clojure \
  -Sdeps "{:deps {io.github.ruseel/snlib-cli {:git/url \"${SNLIB_GITHUB_REPO}\" :git/tag \"${SNLIB_GIT_TAG}\" :git/sha \"${SNLIB_GIT_SHA}\"}}}" \
  -M -m snlib.cli "$@"
