#!/usr/bin/env bash
# Reminds a session to have the security-reviewer agent look at the code as it stands before it ends.
#
#   security-review.sh check    Stop hook: blocks once when the code differs from the last recorded review
#   security-review.sh mark     records that the code as it stands was reviewed (the reviewer runs this)
#   security-review.sh state    prints the fingerprint of the code as it is, without the sweep check and mark do
#
# "Code" is every file git knows or would add under the paths below, by content, so committing a change does
# not make it count as reviewed. Markdown is left out: writing docs asks for no review. The project's settings
# file is in, because it registers this hook and says which commands run without asking; `check` and `mark`
# first move the approvals a session left in it to the local settings file (see sweep).
#
# This is a reminder, not a lock: anyone who can run `mark` or edit the record can silence it. It blocks a
# stop once and lets the next one through, so a session can always end.
set -uo pipefail

MARK=.claude/security-reviewed
PATHS=(apps contracts core gov mappack testnet .claude/hooks .claude/settings.json ':(exclude)*.md')

SETTINGS=.claude/settings.json
LOCAL=.claude/settings.local.json

# Claude Code writes an approval given during a session ("always allow") into the project's settings file,
# which is tracked, shared and part of the fingerprint below. That file is kept by hand: an allow rule belongs
# in it once a person staged or committed it. Any other rule found there is one person's approval, and is
# moved to the local settings file, which git ignores and the fingerprint leaves out. The rule keeps working
# for that person; it stops being everyone's, and stops asking for a review each time a command is approved.
#
# To add a rule for everyone: edit the file and `git add` it. The sweep cannot tell an approval from an edit:
# any allow rule that is neither staged nor committed is moved, whoever wrote it, and each one is named on the
# error stream so whoever is reviewing sees it. The file must stay as `jq .` prints it, or it is left alone.
# Undoing a commit that added a rule (`git reset HEAD~1`) leaves that rule neither staged nor committed, so it is
# moved like any other: stage the file again before stopping.
# Best effort: without jq, or if anything fails, the files are left as they are.
sweep() {
  local base extra tmp rule
  command -v jq >/dev/null && [ -f "$SETTINGS" ] && [ ! -L "$SETTINGS" ] && [ ! -L "$LOCAL" ] || return 0
  # Rewriting a file jq would format differently would show up as a change to all of it.
  jq . "$SETTINGS" 2>/dev/null | cmp -s - "$SETTINGS" || return 0
  # Shared rules are the staged ones and the committed ones: unstaging a commit must not sweep what it held.
  base="$({ git show ":$SETTINGS"; git show "HEAD:$SETTINGS"; } 2>/dev/null | jq -cs 'map(.permissions.allow // []) | add // []' 2>/dev/null)" && [ -n "$base" ] || return 0
  git cat-file -e ":$SETTINGS" 2>/dev/null || return 0
  extra="$(jq -c --argjson base "$base" '(.permissions.allow // []) - $base' "$SETTINGS" 2>/dev/null)" || return 0
  [ -n "$extra" ] && [ "$extra" != "[]" ] || return 0
  [ -f "$LOCAL" ] || echo '{}' >"$LOCAL" || return 0
  # Into the local file first, out of the shared one second: a failure in between leaves a rule in both, never
  # in neither. Each file is replaced in one step, so a full disk or a kill cannot leave half of one behind.
  tmp="$(mktemp .claude/.sweep.XXXXXX)" || return 0
  if jq --argjson extra "$extra" '.permissions.allow = ((.permissions.allow // []) + $extra | unique)' "$LOCAL" >"$tmp" 2>/dev/null \
    && [ -s "$tmp" ] && chmod 644 "$tmp" && mv -f "$tmp" "$LOCAL"; then
    tmp="$(mktemp .claude/.sweep.XXXXXX)" || return 0
    if jq --argjson extra "$extra" '.permissions.allow -= $extra' "$SETTINGS" >"$tmp" 2>/dev/null \
      && [ -s "$tmp" ] && chmod 644 "$tmp" && mv -f "$tmp" "$SETTINGS"; then
      while IFS= read -r rule; do
        echo "security-review: moved an unstaged allow rule to $LOCAL: $rule" >&2
      done < <(jq -r '.[]' <<<"$extra")
    fi
  fi
  rm -f "$tmp"
}

block() {
  printf '{"decision":"block","reason":"%s"}\n' "$1"
  exit 0
}

# Prints the fingerprint, or fails. Every step is checked by hand: `set -e` does nothing inside a function
# called on the left of `||`.
state() {
  local list sums modules
  list="$(mktemp)" || return 1
  if ! git ls-files -z --cached --others --exclude-standard -- "${PATHS[@]}" | sort -z >"$list"; then
    rm -f "$list"
    return 1
  fi
  sums="$(
    regular=()
    while IFS= read -r -d '' f; do
      if [ -f "$f" ] && [ ! -L "$f" ]; then regular+=("$f"); else printf 'other %s %s\n' "$f" "$(readlink "$f" 2>/dev/null || true)"; fi
    done <"$list"
    [ "${#regular[@]}" -eq 0 ] || printf '%s\0' "${regular[@]}" | xargs -0 sha256sum
  )" || { rm -f "$list"; return 1; }
  rm -f "$list"
  modules="$(git submodule status)" || return 1
  printf '%s\n%s\n' "$sums" "$modules" | sha256sum | cut -d' ' -f1
}

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)" || {
  [ "${1:-check}" = check ] && block "The security review hook could not find the repository. Run the security-check skill and tell the user the hook is broken."
  exit 1
}

case "${1:-check}" in
  state) state ;;
  mark)
    sweep
    now="$(state)" && [ -n "$now" ] || { echo "could not fingerprint the code; nothing recorded" >&2; exit 1; }
    printf '%s\n' "$now" >"$MARK"
    ;;
  check)
    # Already asked once in this turn: let the session end instead of looping.
    # Run by hand with nothing piped in, there is no hook input to read.
    if [ ! -t 0 ] && grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then exit 0; fi
    sweep
    now="$(state)" && [ -n "$now" ] || block "The security review hook failed to read the code. Run the security-check skill and tell the user the hook is broken."
    if [ ! -f "$MARK" ]; then
      # Nothing recorded yet, as on a fresh clone: only uncommitted work asks for a review.
      [ -z "$(git status --porcelain -- "${PATHS[@]}")" ] && exit 0
    elif [ "$(cat "$MARK")" = "$now" ]; then
      exit 0
    fi
    block "Code under apps/, contracts/, core/, gov/, mappack/, testnet/ or .claude/hooks, or .claude/settings.json (its hooks, or an allow rule someone staged), changed since the last recorded security review. Run the security-check skill now: it maps the change to the documented invariants and launches the security-reviewer agent. Then report its findings to the user."
    ;;
  *) echo "usage: $0 check|mark|state" >&2; exit 2 ;;
esac
