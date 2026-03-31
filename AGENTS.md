

# Gotchas
- Hooks auto-run on `git commit` via `prek`.

## When checking snlib-cli.sh would running
To check shell file(skills/snlib-cli/scripts/snlib-cli.sh) would run in user's environment
  maven local repo location should be override, not to use dev machine's local repo.

adding :mavn/local-repo works
```
clojure -Srepro \
    -Sdeps '{:mvn/local-repo "/tmp/snlib-test-m2"
             :deps {io.github.ruseel/snlib-cli
                    {:local/root ".../.../snlib-cli.jar"}}}' \
    -M -m snlib.cli --help
```

adding JAVA_TOOL_OPTIONS=-Dmaven.repo.local=... is not working.
