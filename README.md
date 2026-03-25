# snlib-cli

Clojure CLI for Seongnam Library (`snlib.go.kr`) tasks, plus an OpenClaw/ClawHub skill bundle under `skills/snlib-cli`.

## Local Usage

```bash
clojure -M -m snlib.cli --help
skills/snlib-cli/scripts/snlib-cli --help
```

## Release Process

The published skill does not bundle compiled binaries or source snapshots. The launcher in `skills/snlib-cli/scripts/snlib-cli` resolves this repo at runtime via tools.deps, so each release should repin the launcher to the release tag/commit before publishing.

Before release:

1. Update `skills/snlib-cli/scripts/snlib-cli` so `SNLIB_GITHUB_REPO` points to the public clone URL you want ClawHub users to fetch.
2. Create the release tag on the commit you want to publish.
3. Run the pin helper to update the skill launcher defaults:

```bash
scripts/pin-release <tag>
# or
scripts/pin-release <tag> <sha>
```

4. Smoke-test the launcher:

```bash
skills/snlib-cli/scripts/snlib-cli --help
```

5. Publish the skill folder to ClawHub:

```bash
clawhub publish ./skills/snlib-cli --slug snlib-cli --name "snlib-cli" --version <version> --tags latest
```

`scripts/pin-release` only rewrites the default `SNLIB_GIT_TAG` and `SNLIB_GIT_SHA` values in the skill launcher.
