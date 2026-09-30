# Native binary releases and ClawHub

ClawHub publishes text files, not native executables. GitHub Releases host the
binaries; the skill launcher downloads a matching prebuilt executable, checks
its SHA-256 against the manifest shipped in the skill, and caches it. There is
no compilation, source checkout, or MoonBit dependency on the user's machine.

## Release assets

Each tag `vVERSION` (for example `v0.1.0`) produces:

| Asset | GitHub runner / baseline |
| --- | --- |
| `snlib-cli-darwin-arm64` | macOS 15, ARM64 |
| `snlib-cli-darwin-amd64` | macOS 15, x86-64 |
| `snlib-cli-linux-amd64` | Ubuntu 22.04, glibc, x86-64 |
| `snlib-cli-linux-arm64` | Ubuntu 24.04, glibc, ARM64 |
| `SHA256SUMS` | SHA-256 and filename for all four binaries |

Binaries are dynamically linked to system libraries, including libcurl.
Install the libcurl runtime on Linux (`libcurl4` / `libcurl4t64`, depending
on distribution). Windows, Alpine/musl, and older OS/runtime versions are not
supported by these builds. Do not assume that one host-native binary works
across platforms.

## Publish a GitHub release

Pushes to `moonbit` and `main` run the same four-platform build validation
without publishing. Only a `v*` tag enables the release-publishing job.

1. Commit and push the implementation, tests, and
   `.github/workflows/native-release.yml` to GitHub.
2. Pick a new semver and tag the intended commit:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Watch the **Native release** workflow. It installs MoonBit and native build
   dependencies on each runner, runs offline tests, builds the native CLI,
   and packages the already-built executable.
4. Only after all four builds succeed does the workflow create a draft
   GitHub Release, upload the binaries and `SHA256SUMS`, and publish it.

The workflow uses the official MoonBit installer and records `moon version`
in its logs. It currently installs the latest toolchain. GitHub Actions must
be enabled; the release job needs its configured `contents: write` permission.
ARM runners must be available to the repository. CI execution on GitHub is
required to confirm all platform builds; local tests cannot establish that.

Existing release assets are **not overwritten**. If uploading fails, the
release remains a draft. Inspect/delete an incomplete draft before retrying.
Do not replace binaries under a version already pinned by a published skill.

### Package an existing local build (no upload)

```bash
moon -C impl/moonbit build --target native
bash scripts/package-native.sh
```

This copies the host-native executable into `target/native-release/` with a
platform-specific filename and a `.sha256` sidecar. The packaging script itself
does not build anything. Local packaging is useful for inspection; the normal
GitHub release workflow provides the complete four-platform release.

## Prepare and publish the skill

Install Babashka (`bb`), GitHub CLI (`gh`), and ClawHub CLI, and authenticate
the publishing tools. Wait for the corresponding GitHub release to be public:

```bash
gh release view v0.1.0 --repo ruseel/snlib-cli
bb scripts/clawhub-release.bb prepare --version 0.1.0
bb scripts/clawhub-release.bb publish --version 0.1.0 \
  --changelog "Prebuilt native CLI"
```

`prepare` fetches `SHA256SUMS` from the existing public release. It requires
exactly the four supported asset entries and writes a pinned tag plus their
checksums to `impl/skills/snlib-cli/references/native-release.txt`. It also
generates the library-code references. `publish` runs this preparation again
and uploads the text-only skill bundle to ClawHub. It does not create the
GitHub release or compile a binary.

Generated reference files are ignored by Git but included in the ClawHub upload.
The GitHub tag and the ClawHub semver must match. Missing/draft releases or
invalid checksum manifests stop publication. Maintainers must trust the GitHub
release and its checksum list before publishing; checksums are integrity
checks, not an independent artifact-signing system.

## Runtime and local development

First invocation downloads only the executable matching `uname -s` /
`uname -m` over HTTPS. The pinned checksum is checked before execution and on
every subsequent cache hit. Downloads go into a temporary file and are
atomically moved into a version/hash-specific cache after verification.
Transport diagnostics go to stderr; the CLI alone writes its JSON to stdout.
The download process does not read command input or credentials.

For an unprepared checkout or offline development, bypass downloading with
an explicit, trusted executable:

```bash
export SNLIB_CLI_BINARY="$PWD/impl/moonbit/_build/native/debug/build/cmd/snlib-cli/snlib-cli.exe"
impl/skills/snlib-cli/scripts/snlib-cli.sh --help
```

This override bypasses release checksum verification and is intended for
local development only. `SNLIB_MOONBIT_DIR` is no longer used.

## Offline validation

```bash
python3 test/skill-sh-test/test-skill-launcher.py
python3 test/skill-sh-test/test-release.py
bash -n scripts/package-native.sh impl/skills/snlib-cli/scripts/snlib-cli.sh
```

Tests substitute local executables for curl, gh, and clawhub. They never
contact GitHub, publish a release, or upload a skill.
