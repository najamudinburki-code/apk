#!/usr/bin/env bash
# Pre-commit gate: stop signing keys, passwords and build output from entering history.
#
# Install (once per clone — Git does not sync hooks):
#   cp pre-commit.sh .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
#
# Run by hand against whatever is staged:  ./pre-commit.sh
# Bypass deliberately, for a commit you have checked yourself:  git commit --no-verify
set -u

# A hook inherits the directory the commit ran from, and where you happened to run git is not a
# security decision: match paths from the repository root, always. GIT_DIR can be relative when Git
# starts a hook, so make it absolute before changing directories.
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
if [ -n "$ROOT" ]; then
    GIT_DIR_ABS="$(git rev-parse --absolute-git-dir 2>/dev/null)" || exit 0
    export GIT_DIR="$GIT_DIR_ABS" GIT_WORK_TREE="$ROOT"
    cd "$ROOT" || exit 0
fi

STAGED="$(git diff --cached --name-only --diff-filter=ACMR)"
[ -n "$STAGED" ] || exit 0

# Mirrors .gitignore, because .gitignore is not a check: it does nothing for a file that is already
# tracked, nothing for `git add -f`, and nothing for a password pasted into a source file.
SECRET_PATHS=(
    '(^|/)(keystore|local)\.properties$'
    '\.keystore$' '\.jks$' '\.pem$' '\.p12$' '\.key$'
    '(^|/)dist/' '\.apk$' '\.aab$'
    '(^|/)\.env(\.|$)'
    '(^|/)(id_rsa|id_ed25519|authorized_keys)$'
    '(^|/)backend/(data|dev-data)/'
    '\.(sqlite|db)[0-9]?(-wal|-shm|-journal)?$'
)

args=()
for p in "${SECRET_PATHS[@]}"; do args+=(-e "$p"); done

blocked=""
while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in *.env.example) continue ;; esac     # templates are meant to be read
    if printf '%s\n' "$f" | grep -qE "${args[@]}"; then blocked+="  $f"$'\n'; fi
done <<< "$STAGED"

if [ -n "$blocked" ]; then
    {
        echo "pre-commit: refusing — these staged files must never be committed:"
        printf '%s' "$blocked"
        echo
        echo "Unstage one:  git restore --staged <path>"
        echo "If it was already tracked, .gitignore will not help: git rm --cached <path>, then"
        echo "rotate whatever it carried. History and every clone still have the old copy."
    } >&2
    exit 1
fi

# ── Secrets pasted into a file that is fine to commit ────────────────────────────────────────────
# Heuristic on purpose: it matches the shape of a credential, not the meaning, so it warns and lets
# you decide rather than blocking work that legitimately contains a long string.
CRED_PATTERNS=(
    '-----BEGIN [A-Z ]*PRIVATE KEY'
    'postgres(ql)?://[^ /]*:[^ @]*@'
    'mysql://[^ /]*:[^ @]*@'
    'mongodb(\+srv)?://[^ /]*:[^ @]*@'
    'sk-[A-Za-z0-9_-]{20,}'
    'AIza[0-9A-Za-z_-]{30,}'
    'ghp_[A-Za-z0-9]{20,}'
    'xox[baprs]-[A-Za-z0-9-]{10,}'
    '(store|key|admin|db|user|connection)[_ -]?(password|passwd|secret|api[_-]?key)[[:space:]]*[=:][[:space:]]*[^[:space:],;]{6,}'
    '(api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|installation[_-]?key)[[:space:]]*[=:][[:space:]]*["'"'"'][^"'"'"']{12,}'
)

cred=()
for p in "${CRED_PATTERNS[@]}"; do cred+=(-e "$p"); done

# The whole staged diff is scanned, with no extension whitelist: the usual paste site for a Neon URL or
# an installation key is a deploy file — render.yaml, a workflow, a .properties — and a list limited to
# source languages would walk straight past it. Binary entries contribute no readable added lines.
DIFF=$(git diff --cached --unified=0)

hits=$(printf '%s\n' "$DIFF" | grep '^+' | grep -v '^+++' | grep -icE "${cred[@]}")
if [ "${hits:-0}" -gt 0 ]; then
    {
        echo "pre-commit: $hits added line(s) look like a credential — check them before you push:"
        printf '%s\n' "$DIFF" | grep '^+' | grep -v '^+++' | grep -iE "${cred[@]}" \
            | sed 's/^+ */    /' | sort -u | head -10
    } >&2
fi

exit 0
