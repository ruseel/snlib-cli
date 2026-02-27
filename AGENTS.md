## Formatting & Testing

Hooks auto-run on `git commit` via `prek`. Do not run manually.

Debug commands (only if hooks fail):
- `standard-clj check` / `standard-clj fix`
- `clj -M:test --fail-fast`

## Agent Workflow

1. Make code changes.
2. Run `git commit`.
3. If hooks fail, read output and fix code.
4. If formatter changed files, run `git add -u` then retry commit.
5. Repeat until commit succeeds.
