# snlib-cli

Clojure CLI for Seongnam Library (`snlib.go.kr`) tasks, ClawHub skill bundle under `skills/snlib-cli`.

SeeAlso skills/snlib-cli/SKILL.md

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

4. Smoke-test the pinned launcher behavior:

```bash
skills/snlib-cli/scripts/snlib-cli --help
```

5. Generate Markdown copies of the EDN reference files directly inside `skills/snlib-cli/references/`:

```bash
scripts/clawhub-release.bb prepare
```

6. Publish `skills/snlib-cli` to ClawHub:

```bash
scripts/clawhub-release.bb publish --version <version>
```

Equivalent raw publish command:

```bash
clawhub skill publish ./skills/snlib-cli --slug snlib-cli --name "snlib-cli" --version <version> --tags latest
```

`scripts/pin-release` only rewrites the default `SNLIB_GIT_TAG` and `SNLIB_GIT_SHA` values in the skill launcher.
`scripts/clawhub-release.bb` materializes `references/lib-code.md` plus `references/manage-code.md` inside `skills/snlib-cli/` from the canonical EDN files in `src/snlib/`.

## Local Usage

```bash
clojure -M -m snlib.cli --help
```

For local development, run the repo directly with `clojure -M -m snlib.cli ...`.
The skill launcher under `skills/snlib-cli/scripts/snlib-cli` is a pinned release wrapper
and does not automatically switch to the current checkout.
