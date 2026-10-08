# Contributing

How commits are made in this repository. Two rules matter more than the rest: one commit per reason,
and nothing secret ever reaches `git add`.

## Commit messages: Conventional Commits

`<type>: <imperative summary of what the commit does>` — 72 characters or fewer, no trailing period.
The body, if there is one, explains why, since the diff already shows what.

| Prefix | Use it when | Example |
| --- | --- | --- |
| `feat:` | behaviour a user of the app or dashboard can notice | `feat: hold location to 60s when unplugged and closed` |
| `fix:` | existing behaviour was wrong | `fix: guard setForegroundServiceBehavior to API 31` |
| `docs:` | only documentation changed | `docs: add the distribution checklist` |
| `refactor:` | no behaviour change, no new behaviour | `refactor: extract IntervalPolicy for unit tests` |
| `chore:` | build, tooling, ignore files, dependencies | `chore: ignore keystores and dist output` |

`feat:` is not a synonym for "I wrote code". If the commit only renames, moves or tidies, it is
`refactor:`. If it changes what the app does on the phone, it is `feat:` or `fix:` — and it needs a
handset test to go with it (see `docs/SILENT_VERIFICATION.md`).

### One commit per reason

"Atomic" means the commit would be the thing you revert. `feat: add proximity and fix lint` is not
atomic: it is two reasons, and reverting the feature would also revert an unrelated correctness fix.
Split it:

```
fix: stop lint reporting an unused permission on the location service
feat: remember which boundaries the phone is inside across a restart
```

Stage selectively rather than `git add -A`. Partial staging (`git add -p`, or
`git add <specific path>`) is how one editing session becomes two commits.

## The no-secrets rule

The keystore and the release passwords are the whole upgrade path: lose `release.keystore` and every
installed phone has to be uninstalled before it can take a new APK. Committed, and every clone of
this repository can sign as this app.

`.gitignore` already excludes them, so a normal `git add` refuses to take them:

```
**/local.properties        # SDK path and any dev secret you paste in here
*.keystore  *.jks          # signing keys
keystore.properties        # the passwords that go with them
*.pem  *.p12  *.key        # any other key material
**/dist/  *.apk  *.aab     # build output, including the signed release
**/.env  **/.env.*         # backend and dashboard env (.env.example excepted)
*.sqlite*  *.db            # local databases
backend/data/  backend/dev-data/
replay_pid*.log            # Kotlin/Gradle daemon crash dumps
```

Ignoring is not a check. `.gitignore` does nothing about a file already tracked, nothing about
`git add -f`, and nothing about a password pasted into a `.kt` or `.js` file — which is the usual way
this happens. `pre-commit.sh` at the repository root is the actual gate; see below.

If a secret does get committed, deleting it in a later commit does not remove it — it stays in
history and in every clone and fork. Rotate it (new keystore password, new `installation_key`, new
connection string), then rewrite the history that carries it.

### The pre-commit hook

Git only runs a file named exactly `pre-commit` (no extension) inside the path it looks at, and that
path is per-clone and never synced. Install it once per clone:

```bash
cp pre-commit.sh .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
```

If you would rather the hook update whenever this file does, point Git at the repository root —
`git config core.hooksPath .` runs `./pre-commit`, so you need a copy under that exact name:

```bash
git config core.hooksPath . && cp pre-commit.sh pre-commit
```

`pre-commit` is then a tracked file of its own; keep the two in step, or delete it and go back to the
first form. The hook blocks a commit when a staged path matches a secret pattern, and warns when a
staged diff adds a line that looks like a key or password — that second check is heuristic, so its
judgement is yours, not the script's. `git commit --no-verify` skips both; use it for a commit you
have personally checked, not to get past a message you did not read.

## Branches and pushing

`main` is what gets installed on the phone. Keep `main` at a commit that builds; if a change cannot
be verified on a handset yet, it is a branch, not `main`. Do not push or force-push anything unless
asked — this repository's history is shared with the installation page.

## Before you call Android work done

Unit tests here run on a JVM, where `android.util.Log`, `SharedPreferences`, `org.json` and every
sensor are stubbed or absent. A green test run proves the pure policy objects (`IntervalPolicy`,
`ProximityWatch`, `PermissionPlan`) agree with themselves. It proves nothing about permissions, Doze,
OEM power management, camera or microphone, or a reboot. Those need the phone, and the answer is
"verified on handset" or "not verified" — never "should work".

The same applies to build output: a report file older than the command that supposedly produced it is
not evidence. Re-run, wait for it to finish, read the file.
