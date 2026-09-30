# snlib-cli

An skill for [Seongnam Library (성남시 도서관)](https://snlib.go.kr).
Search books, check your loans and reservations, and request interlibrary loans
or new books through your AI assistant.

## Install

Install [snlib-cli from ClawHub](https://clawhub.ai/ruseel/snlib-cli) in your
skill-enabled assistant. With the ClawHub CLI:

```bash
clawhub install snlib-cli
```

The skill downloads the appropriate native CLI on first use, verifies its
checksum, and caches it. You do not need build tools or this repository to use.

### Requirements

- macOS on Apple Silicon, or glibc Linux on x86-64/ARM64.
- Bash, curl, and either `sha256sum` or `shasum`.
- The system libcurl runtime. On Debian/Ubuntu, install `libcurl4` or
  `libcurl4t64`, as appropriate for your distribution.
- Network access to GitHub for the first download and to `snlib.go.kr` for
  library operations.

Linux builds target Ubuntu 22.04 (x86-64) and Ubuntu 24.04 (ARM64).
Intel Macs, Windows, and Alpine/musl are not supported by the prebuilt binaries.

## What you can ask

Public book search does not require login. For example:

- “판교도서관에서 제2차 세계대전 관련 책을 찾아줘.”
- “이 책을 소장한 성남시 도서관을 찾아줘.”

After logging in, you can ask:

- “내 대출 현황과 반납 기한을 알려줘.”
- “내 대출 이력과 예약 현황을 확인해줘.”
- “관심 도서함을 보여줘.”
- “상호대차 신청 현황을 확인해줘.”
- “희망도서 신청 내역과 상세 내용을 알려줘.”
- “이 책을 상호대차로 신청할 수 있도록 준비해줘.”
- “이 책의 희망도서 신청을 준비해줘.”

The skill prepares requests first. Your assistant should show you the book,
receiving library, and any contact or consent information, then ask for your
explicit confirmation before submitting.

## Log in safely

Account features require your Seongnam Library credentials. Instead of putting
your password in chat, shell history, or command arguments, create a private
JSON file outside this repository:

```json
{"user_id":"YOUR_ID","password":"YOUR_PASSWORD"}
```

Restrict access to the file:

```bash
chmod 600 /path/to/private-login.json
```

Tell your assistant to use that file for login, or run the launcher from the
installed skill directory:

```bash
scripts/snlib-cli.sh login < /path/to/private-login.json
```

Delete the file when no longer needed. The CLI does not save your password.
It saves session cookies with owner-only permissions under
`$XDG_CONFIG_HOME/snlib-cli` (default `~/.config/snlib-cli`). Sessions expire
after three hours, or sooner if the library rejects them; log in again when
asked.

Account results and prepared requests can contain personal information.
Avoid sharing them in public chats or logs.

## Run commands directly

From the installed skill directory:

```bash
scripts/snlib-cli.sh --help

# Search without logging in.
printf '%s\n' '{"keyword":"삼국지"}' | scripts/snlib-cli.sh search-books

# Check loans after logging in.
printf '%s\n' '{}' | scripts/snlib-cli.sh loan-status
```

Commands take exactly one JSON object on stdin and return a JSON result.
Check `outcome`, `code`, and `message`: a successful process exit alone does not
mean the library operation succeeded.

For command inputs and available workflows, see the
[skill guide](impl/skills/snlib-cli/SKILL.md) and
[command reference](docs/moonbit-commands.md).

## Troubleshooting

- **Session missing, expired, or rejected:** log in again using your private file.
- **Download failed:** check access to GitHub and retry.
- **Checksum mismatch:** do not run the downloaded file. Reinstall the trusted
  skill or remove the corrupted cached binary and retry.
- **Unsupported platform or missing runtime library:** check the requirements
  above; the skill does not build a binary on your machine.
- **Library request failed:** do not assume it succeeded. The service may be
  unavailable or its pages may have changed.
- **Submission error:** check your interlibrary-loan or hope-book request status
  before retrying. The library may have accepted the request even if the CLI
  could not confirm it.

Requests are preparation-only by default. Submission requires explicit
confirmation; never automatically retry a submission after a network or
session-save error.
