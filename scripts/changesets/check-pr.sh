#!/usr/bin/env bash
#
# Pull-request gate for player-facing release notes, run by .github/workflows/changeset.yml (CONTRIBUTING.md ›
# Release notes). A pull request with a user-visible change — a feat / fix / perf commit (scopes and `!` allowed), or
# such a PR title, which is what a squash merge would commit — must ADD a note under changes/unreleased/. The
# `no-changeset` label (NO_CHANGESET=true) waives it for an internal-only change.
#
#   BASE_SHA=<base> HEAD_SHA=<head> [PR_TITLE=<title>] [NO_CHANGESET=true] scripts/changesets/check-pr.sh
set -euo pipefail

: "${BASE_SHA:?BASE_SHA is required}" "${HEAD_SHA:?HEAD_SHA is required}"
user_visible='^(feat|fix|perf)(\([^)]*\))?!?: '

fork_point=$(git merge-base "$BASE_SHA" "$HEAD_SHA")
triggers=$(
    {
        git log --no-merges --format=%s "$fork_point..$HEAD_SHA"
        printf '%s\n' "${PR_TITLE:-}"
    } | grep -E "$user_visible" || true
)
if [ -z "$triggers" ]; then
    echo "No feat / fix / perf commit: no release note needed."
    exit 0
fi

notes=$(git diff --name-only --no-renames --diff-filter=A "$fork_point" "$HEAD_SHA" -- 'changes/unreleased/*.properties')
if [ -n "$notes" ]; then
    printf 'Release note(s) added:\n%s\n' "$notes"
    exit 0
fi
if [ "${NO_CHANGESET:-false}" = "true" ]; then
    echo "::notice title=Release note waived::the no-changeset label marks this pull request as internal-only."
    exit 0
fi

explain="Player-facing changes need a release note: add changes/unreleased/<short-slug>.properties (UTF-8) with
type=feat|fix|perf, en=<one sentence for players> and fr=<the same in French> (es optional), as described in
CONTRIBUTING.md › Release notes. Internal-only change? A maintainer can add the no-changeset label instead.

User-visible commit(s) in this pull request:
$triggers"
echo "::error title=Missing release note::A feat / fix / perf change must add a note under changes/unreleased/ (or carry the no-changeset label)."
echo "$explain"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '### Missing release note\n\n```\n%s\n```\n' "$explain" >>"$GITHUB_STEP_SUMMARY"
fi
exit 1
