#!/usr/bin/env bash
#
# Files the unreleased player-facing release notes under the version of the open release-please PR, ON that PR's
# branch, so merging the release PR archives them in the same change as its CHANGELOG entry and version bump:
#
#     changes/unreleased/<slug>.properties  ->  changes/<version>/<slug>.properties
#
#   scripts/changesets/assign-release-notes.sh [--dry-run] <release-branch>
#
# Run by .github/workflows/release-notes.yml after every release-please run and after any human push to the release
# branch, from a full clone (history + tags) whose `origin` it can push to. It never touches main, and a note is only
# ever moved, never dropped. Idempotent: with nothing new to file, it pushes nothing.
#
# Why it re-runs: release-please regenerates its branch (one fresh commit on main's HEAD, force-pushed) whenever the
# release notes change, which discards this filing; the next run files them again. When main moves WITHOUT a
# regeneration (only commits release-please doesn't list) and that brought note changes, the branch is rebuilt on main
# instead: release-please's commit and any human commit replayed, the previous filing recomputed, so a note merged in
# the meantime is not left behind.
#
# The version is the one in .release-please-manifest.json on the release branch. A note whose adding commit is already
# part of a release (it was merged while that release PR was being merged, and missed its filing) is filed under THAT
# release, which is also the version its build displayed it under (unfiled notes are shown as the version being built).
set -euo pipefail

dry_run=false
if [ "${1:-}" = "--dry-run" ]; then
    dry_run=true
    shift
fi
branch="${1:?usage: assign-release-notes.sh [--dry-run] <release-branch>}"
remote="${REMOTE:-origin}"
main="${MAIN_BRANCH:-main}"
tag_prefix="${TAG_PREFIX:-wakfu-autobuilder-}"
# Marks this script's commits, so a rebuild drops them instead of replaying them.
marker="Release-Notes-Filed-By: scripts/changesets/assign-release-notes.sh"
bot_name="github-actions[bot]"
bot_email="41898282+github-actions[bot]@users.noreply.github.com"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-$bot_name}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-$bot_email}"

die() {
    echo "::error title=Release notes not filed::$*" >&2
    exit 1
}

git fetch --quiet --tags "$remote" \
    "+refs/heads/$main:refs/remotes/$remote/$main" \
    "+refs/heads/$branch:refs/remotes/$remote/$branch"
main_sha=$(git rev-parse "refs/remotes/$remote/$main")
head_sha=$(git rev-parse "refs/remotes/$remote/$branch")
fork_point=$(git merge-base "$main_sha" "$head_sha")

# Work in a throwaway worktree: the caller's checkout is never touched.
repo=$(pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/release-notes.XXXXXX")
cleanup() {
    cd "$repo"
    git worktree remove --force "$work" 2>/dev/null || rm -rf "$work"
}
trap cleanup EXIT
git worktree add --quiet --detach "$work" "$head_sha"
cd "$work"

if [ "$fork_point" != "$main_sha" ] && ! git diff --quiet "$fork_point" "$main_sha" -- changes; then
    echo "Notes changed on $main since $branch was generated: rebuilding it on $main."
    git reset --quiet --hard "$main_sha"
    for commit in $(git rev-list --reverse --no-merges "$fork_point..$head_sha"); do
        if git log -1 --format=%B "$commit" | grep -qxF "$marker"; then
            continue
        fi
        # --keep-redundant-commits: a commit whose change main already has replays as an empty commit, not a failure.
        if ! git cherry-pick --allow-empty --keep-redundant-commits "$commit" >/dev/null; then
            git cherry-pick --abort || true
            die "replaying $(git log -1 --format='%h %s' "$commit") onto $main conflicts. Rebase $branch onto $main by hand (or close the release PR: release-please reopens it on the next push to $main), then re-run this workflow."
        fi
    done
fi

version=$(sed -n 's/.*"\."[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' .release-please-manifest.json)
[ -n "$version" ] || die "no \".\" version in .release-please-manifest.json on $branch"
if git rev-parse --quiet --verify "refs/tags/$tag_prefix$version" >/dev/null; then
    die "$version is already released (tag $tag_prefix$version): $branch is not a pending release."
fi

filed=""
for note in changes/unreleased/*.properties; do
    [ -e "$note" ] || continue # no unreleased note: the glob stayed literal
    target=$version
    added_by=$(git log -1 --no-renames --diff-filter=A --format=%H -- "$note")
    if [ -n "$added_by" ]; then
        shipped_in=$(git tag --list "$tag_prefix*" --contains "$added_by" --sort=v:refname | sed -n 1p)
        if [ -n "$shipped_in" ]; then
            target=${shipped_in#"$tag_prefix"}
        fi
    fi
    destination="changes/$target/$(basename "$note")"
    [ ! -e "$destination" ] || die "$destination already exists: rename $note on $main."
    mkdir -p "changes/$target"
    git mv "$note" "$destination"
    filed="$filed- $note -> $destination"$'\n'
done

if [ -n "$filed" ]; then
    count=$(printf '%s' "$filed" | grep -c .)
    git commit --quiet --author="$bot_name <$bot_email>" -F - <<EOF
chore: file $count release note(s) under $version

The release PR moves this release's player-facing notes out of changes/unreleased/
(scripts/changesets/assign-release-notes.sh):

$filed
$marker
EOF
fi

new_sha=$(git rev-parse HEAD)
if [ "$(git rev-parse "$new_sha^{tree}")" = "$(git rev-parse "$head_sha^{tree}")" ]; then
    echo "$branch already has its notes filed under $version: nothing to push."
    exit 0
fi

report=${filed:-"- no note to move: $branch only replayed onto $main"$'\n'}
printf 'Release notes of %s on %s:\n%s' "$version" "$branch" "$report"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '### Release notes of %s\n\n%s' "$version" "$report" >>"$GITHUB_STEP_SUMMARY"
fi
if $dry_run; then
    echo "--dry-run: $branch would move from ${head_sha:0:12} to ${new_sha:0:12}:"
    git --no-pager log --format='  %h %s' "$main_sha..$new_sha"
    exit 0
fi
if git push --quiet --force-with-lease="refs/heads/$branch:$head_sha" "$remote" "$new_sha:refs/heads/$branch"; then
    echo "Pushed $branch at ${new_sha:0:12}."
    exit 0
fi
# A rejected lease means the branch moved since we read it (release-please regenerated it, or someone pushed): the run
# that update triggered files the notes on top of it. Anything else is a real failure.
if [ "$(git ls-remote --heads "$remote" "refs/heads/$branch" | cut -f1)" != "$head_sha" ]; then
    echo "::notice title=Release notes::$branch moved while filing; the run triggered by that update files the notes."
    exit 0
fi
die "could not push $branch."
