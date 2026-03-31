# snlib-cli

Clojure CLI and library for Seongnam Library (`snlib.go.kr`) tasks.
The ClawHub skill bundle lives under `skills/snlib-cli`.

See also: `skills/snlib-cli/SKILL.md`

## Local CLI usage

```bash
clojure -M -m snlib.cli --help
```

For local development, run the repo directly with `clojure -M -m snlib.cli ...`.
The launcher under `skills/snlib-cli/scripts/snlib-cli.sh` is a pinned release wrapper
and does not automatically switch to the current checkout.

## Use as a library with `clj -Sdeps`

### Install locally first

Build and install a Maven-style artifact into `~/.m2`:

```bash
clj -T:build install :version '"0.0.1-SNAPSHOT"'
```

Then consume it with normal `tools.deps` Maven coordinates:

```bash
clj -Srepro \
  -Sdeps '{:deps {io.github.ruseel/snlib-cli {:mvn/version "0.0.1-SNAPSHOT"}}}' \
  -e '(require (quote snlib.core)) (println :ok)'
```

After publishing to Maven Central / Sonatype Central, use the same form with the
published version string.

## Maven Central / Sonatype Central

This repo now includes a `tools.build` pipeline for generating:

- main jar
- standalone pom
- sources jar
- javadoc jar
- `.asc`, `.md5`, `.sha1` files
- Central Portal upload bundle zip

Build metadata lives in `build-config.edn`.
Before the first Central release, update the `:licenses` entry there.
`build-config.edn :server-id` must match the `<server><id>...` entry in
`~/.m2/settings.xml` that contains your Central Portal token username/password.

### Build tasks

Show help:

```bash
clj -T:build help
```

Build Maven artifacts:

```bash
clj -T:build jar :version '"0.1.0"'
```

Install locally:

```bash
clj -T:build install :version '"0.1.0-SNAPSHOT"'
```

Sign artifacts with GPG:

```bash
clj -T:build sign :version '"0.1.0"'
# or
clj -T:build sign :version '"0.1.0"' :gpg-key '"<KEYID>"'
```

Create the Central bundle zip:

```bash
clj -T:build bundle :version '"0.1.0"'
```

Upload to Sonatype Central Portal:

```bash
clj -T:build deploy :version '"0.1.0"' :server-id '"central"'
```

By default, `deploy` uploads with `USER_MANAGED` so you can inspect validation
first. For automatic publish after validation:

```bash
clj -T:build deploy \
  :version '"0.1.0"' \
  :server-id '"central"' \
  :publishing-type '"AUTOMATIC"'
```

Check deployment status:

```bash
clj -T:build status :deployment-id '"<deployment-id>"' :server-id '"central"'
```

Publish a validated `USER_MANAGED` deployment:

```bash
clj -T:build publish :deployment-id '"<deployment-id>"' :server-id '"central"'
```

Drop a failed or unwanted deployment:

```bash
clj -T:build drop :deployment-id '"<deployment-id>"' :server-id '"central"'
```

## ClawHub release process

The published skill does not bundle compiled binaries or source snapshots.
The launcher resolves this project at runtime via `clojure -Sdeps`.
It now supports both Maven coordinates and git deps:

- set `SNLIB_MVN_VERSION` to use the published Central artifact
- otherwise it falls back to the pinned git tag/sha

Before release:

1. Update `skills/snlib-cli/scripts/snlib-cli.sh` so `SNLIB_GITHUB_REPO` points
   to the public clone URL you want ClawHub users to fetch.
2. If you are still using git fallback pinning, update the launcher defaults:

   ```bash
   scripts/pin-release <tag>
   # or
   scripts/pin-release <tag> <sha>
   ```

3. Smoke-test the launcher behavior:

   ```bash
   skills/snlib-cli/scripts/snlib-cli.sh --help
   ```

4. Generate Markdown copies of the EDN reference files inside
   `skills/snlib-cli/references/`:

   ```bash
   scripts/clawhub-release.bb prepare
   ```

5. Publish `skills/snlib-cli` to ClawHub:

   ```bash
   scripts/clawhub-release.bb publish --version <version>
   ```

Equivalent raw publish command:

```bash
clawhub skill publish ./skills/snlib-cli --slug snlib-cli --name "snlib-cli" --version <version> --tags latest
```

`scripts/pin-release` rewrites the default `SNLIB_GIT_TAG` and `SNLIB_GIT_SHA`
values in the skill launcher.
`scripts/clawhub-release.bb` materializes `references/lib-code.md` plus
`references/manage-code.md` inside `skills/snlib-cli/` from the canonical EDN
files in `src/snlib/`.
