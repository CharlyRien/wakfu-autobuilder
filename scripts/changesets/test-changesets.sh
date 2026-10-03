#!/usr/bin/env bash
#
# Scenario tests for the release-note tooling, on throwaway local repositories (no network, no GitHub): the filing of
# notes on the release PR (assign-release-notes.sh), the pull-request gate (check-pr.sh) and the commit-msg hook.
# Simulates release-please (a fresh commit on main, force-pushed to its branch) and GitHub's rebase merge.
#
#   scripts/changesets/test-changesets.sh        (also run by the Build workflow)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
assign="$here/assign-release-notes.sh"
check="$here/check-pr.sh"
hooks=$(cd "$here/../git-hooks" && pwd)

root=$(mktemp -d "${TMPDIR:-/tmp}/changesets-test.XXXXXX")
trap 'rm -rf "$root"' EXIT
# Isolated from the caller's git configuration (hooks path, signing, default branch…) and from the CI step summary.
export GIT_CONFIG_GLOBAL="$root/gitconfig" GIT_CONFIG_NOSYSTEM=1
unset GITHUB_STEP_SUMMARY GIT_DIR GIT_WORK_TREE
git config --global init.defaultBranch main
git config --global user.name tester
git config --global user.email tester@example.com
git config --global advice.detachedHead false

rp=release-please--branches--main--components--wakfu-autobuilder
failures=0
ok() { echo "ok - $*"; }
fail() {
    echo "FAIL - $*" >&2
    failures=$((failures + 1))
}
# expect_files <description> <ref> <expected notes under changes/, space-separated, sorted>; the unreleased/ anchor
# file must be there too, always.
expect_files() {
    local listing actual
    listing=$(git -C "$root/origin.git" ls-tree -r --name-only "$2" -- changes)
    actual=$(printf '%s\n' "$listing" | grep -v '^changes/unreleased/\.gitkeep$' | tr '\n' ' ' | sed 's/ $//')
    if ! printf '%s\n' "$listing" | grep -qx 'changes/unreleased/\.gitkeep'; then
        fail "$1: changes/unreleased/.gitkeep is gone"
    elif [ "$actual" = "$3" ]; then
        ok "$1"
    else
        fail "$1: expected [$3], got [$actual]"
    fi
}
rev() { git -C "$root/origin.git" rev-parse "$1"; }

# ---------------------------------------------------------------------------------------------------------------------
# assign-release-notes.sh
# ---------------------------------------------------------------------------------------------------------------------
git init --quiet --bare "$root/origin.git"
git clone --quiet "$root/origin.git" "$root/dev" 2>/dev/null
cd "$root/dev"
manifest() { printf '{\n    ".": "%s"\n}\n' "$1" >.release-please-manifest.json; }
note() {
    mkdir -p changes/unreleased
    printf 'type=%s\nen=English %s\nfr=Français %s\n' "$2" "$1" "$1" >"changes/unreleased/$1.properties"
}
on() {
    git fetch --quiet origin
    git checkout --quiet --detach "origin/$1"
}
# land <subject> [<slug>:<type>...]: a pull request rebase-merged into main, adding those notes
land() {
    local subject=$1
    shift
    on main
    for spec in "$@"; do note "${spec%%:*}" "${spec##*:}"; done
    echo "$subject" >>history.txt
    git add -A
    git commit --quiet -m "$subject"
    git push --quiet origin HEAD:main
}
# release_please <version>: what release-please does on a push to main whose notes changed — one fresh commit on
# main's HEAD (manifest, CHANGELOG), force-pushed to its branch, dropping whatever was there.
release_please() {
    on main
    manifest "$1"
    { printf '## %s\n\n' "$1" && cat CHANGELOG.md; } >CHANGELOG.new && mv CHANGELOG.new CHANGELOG.md
    git commit --quiet -am "chore(main): release wakfu-autobuilder $1"
    git push --quiet --force origin "HEAD:refs/heads/$rp"
}
# file_notes [--dry-run]: what the workflow does — a fresh full clone, then the script, with no git identity configured
# (like a CI runner). Its exit status is kept.
: >"$root/empty-gitconfig"
file_notes() {
    rm -rf "$root/ci"
    git clone --quiet "$root/origin.git" "$root/ci" 2>/dev/null
    (cd "$root/ci" && GIT_CONFIG_GLOBAL="$root/empty-gitconfig" "$assign" "$@" "$rp")
}
# merge_release <version>: GitHub's rebase merge of the release PR onto main, then release-please's tag
merge_release() {
    on main
    for commit in $(git rev-list --reverse "origin/main..origin/$rp"); do
        git cherry-pick --allow-empty --keep-redundant-commits "$commit" >/dev/null
    done
    git push --quiet origin HEAD:main
    git tag "wakfu-autobuilder-$1"
    git push --quiet origin "wakfu-autobuilder-$1"
}

manifest 1.0.0
echo "# Changelog" >CHANGELOG.md
mkdir -p changes/1.0.0 changes/unreleased
printf 'type=feat\nen=First\nfr=Premier\n' >changes/1.0.0/first.properties
echo "# keeps changes/unreleased/ from ever being empty" >changes/unreleased/.gitkeep
git add -A
git commit --quiet -m "chore: initial"
git push --quiet origin HEAD:main
git tag wakfu-autobuilder-1.0.0
git push --quiet origin wakfu-autobuilder-1.0.0

echo "# A release PR files every unreleased note under its version"
land "feat: alpha" alpha:feat
land "fix: beta" beta:fix
release_please 1.1.0
file_notes
expect_files "the release branch files them under 1.1.0" "$rp" \
    "changes/1.0.0/first.properties changes/1.1.0/alpha.properties changes/1.1.0/beta.properties"
expect_files "main is untouched" main \
    "changes/1.0.0/first.properties changes/unreleased/alpha.properties changes/unreleased/beta.properties"
[ "$(git -C "$root/origin.git" log -1 --format=%s "$rp")" = "chore: file 2 release note(s) under 1.1.0" ] &&
    ok "one filing commit on top of release-please's" || fail "unexpected filing commit"

echo "# A re-run with nothing new pushes nothing"
before=$(rev "$rp")
file_notes
[ "$(rev "$rp")" = "$before" ] && ok "idempotent" || fail "a re-run moved the branch"

echo "# release-please regenerating its branch drops the filing; the next run files again, new notes included"
land "feat: gamma" gamma:feat
release_please 1.1.0
expect_files "(the regeneration dropped the filing)" "$rp" \
    "changes/1.0.0/first.properties changes/unreleased/alpha.properties changes/unreleased/beta.properties changes/unreleased/gamma.properties"
file_notes
expect_files "re-filed after the regeneration" "$rp" \
    "changes/1.0.0/first.properties changes/1.1.0/alpha.properties changes/1.1.0/beta.properties changes/1.1.0/gamma.properties"

echo "# A note merged WITHOUT a regeneration (docs-only push) is filed too; a human edit on the release PR survives"
on "$rp"
echo "reworded for players" >>CHANGELOG.md
git commit --quiet -am "docs: reword the release notes"
git push --quiet origin "HEAD:refs/heads/$rp"
land "docs: the note of an already-merged fix" delta:fix
file_notes
expect_files "the stale branch is rebuilt on main and files the new note" "$rp" \
    "changes/1.0.0/first.properties changes/1.1.0/alpha.properties changes/1.1.0/beta.properties changes/1.1.0/delta.properties changes/1.1.0/gamma.properties"
git -C "$root/origin.git" merge-base --is-ancestor main "$rp" && ok "the release branch is now based on main" || fail "not based on main"
subjects=$(git -C "$root/origin.git" log --format=%s "main..$rp" | tr '\n' '|')
[ "$subjects" = "chore: file 4 release note(s) under 1.1.0|docs: reword the release notes|chore(main): release wakfu-autobuilder 1.1.0|" ] &&
    ok "release-please's commit and the human edit replayed, one fresh filing" || fail "unexpected history: $subjects"
git -C "$root/origin.git" show "$rp:CHANGELOG.md" | grep -q "reworded for players" && ok "the human edit is kept" || fail "human edit lost"

echo "# A note that raced the release merge ships unfiled; the next release PR files it under the release that shipped it"
land "fix: epsilon, merged right before the release" epsilon:fix
merge_release 1.1.0
expect_files "(1.1.0 shipped epsilon unfiled — its build showed it as 1.1.0)" wakfu-autobuilder-1.1.0 \
    "changes/1.0.0/first.properties changes/1.1.0/alpha.properties changes/1.1.0/beta.properties changes/1.1.0/delta.properties changes/1.1.0/gamma.properties changes/unreleased/epsilon.properties"
land "feat: zeta" zeta:feat
release_please 1.2.0
file_notes
expect_files "epsilon goes to 1.1.0, zeta to 1.2.0" "$rp" \
    "changes/1.0.0/first.properties changes/1.1.0/alpha.properties changes/1.1.0/beta.properties changes/1.1.0/delta.properties changes/1.1.0/epsilon.properties changes/1.1.0/gamma.properties changes/1.2.0/zeta.properties"

echo "# --dry-run pushes nothing"
land "feat: eta" eta:feat
release_please 1.2.0
before=$(rev "$rp")
file_notes --dry-run >/dev/null
[ "$(rev "$rp")" = "$before" ] && ok "dry run leaves the branch alone" || fail "dry run pushed"

echo "# Refusals leave the branch alone"
on main
mkdir -p changes/1.2.0
printf 'type=fix\nen=Theta\nfr=Thêta\n' >changes/1.2.0/theta.properties
note theta fix
git add -A
git commit --quiet -m "fix: theta, written twice"
git push --quiet origin HEAD:main
release_please 1.2.0
before=$(rev "$rp")
if file_notes >/dev/null 2>&1; then fail "a name clash was overwritten"; else ok "a name clash fails instead of overwriting"; fi
[ "$(rev "$rp")" = "$before" ] && ok "(branch untouched)" || fail "branch moved on a refusal"
on main
git rm --quiet changes/unreleased/theta.properties
git commit --quiet -m "fix: drop the duplicate theta note"
git push --quiet origin HEAD:main
release_please 1.1.0
if file_notes >/dev/null 2>&1; then fail "filed under an already-released version"; else ok "an already-released version is refused"; fi

echo "# A pull request branched before a release keeps its note in changes/unreleased/ when rebased after it"
on main
git checkout --quiet -B late-pr
note iota feat
git add -A
git commit --quiet -m "feat: iota, branched before 1.2.0"
release_please 1.2.0
file_notes >/dev/null
merge_release 1.2.0
expect_files "1.2.0 shipped with every note filed (only the anchor left in unreleased/)" wakfu-autobuilder-1.2.0 \
    "changes/1.0.0/first.properties changes/1.1.0/alpha.properties changes/1.1.0/beta.properties changes/1.1.0/delta.properties changes/1.1.0/epsilon.properties changes/1.1.0/gamma.properties changes/1.2.0/eta.properties changes/1.2.0/theta.properties changes/1.2.0/zeta.properties"
git checkout --quiet late-pr
# Default merge.directoryRenames: without the anchor, git would see unreleased/ renamed to 1.2.0/ and move iota there.
if git rebase --quiet origin/main >/dev/null 2>&1; then
    [ -f changes/unreleased/iota.properties ] && [ ! -e changes/1.2.0/iota.properties ] &&
        ok "the rebased note stays unreleased" || fail "the rebased note moved: $(git ls-files changes | tr '\n' ' ')"
else
    git rebase --abort
    fail "rebasing a note across a release conflicts"
fi

# ---------------------------------------------------------------------------------------------------------------------
# check-pr.sh
# ---------------------------------------------------------------------------------------------------------------------
git init --quiet "$root/pr"
cd "$root/pr"
echo base >file.txt
git add -A
git commit --quiet -m "chore: base"
base=$(git rev-parse HEAD)
# pull_request <subject>... : a branch off base with one commit per subject (a "+note" subject adds a note too)
pull_request() {
    git checkout --quiet --detach "$base"
    for subject in "$@"; do
        echo "$subject" >>file.txt
        case "$subject" in *+note*)
            mkdir -p changes/unreleased
            printf 'type=feat\nen=E\nfr=F\n' >"changes/unreleased/n$RANDOM.properties"
            ;;
        esac
        git add -A
        git commit --quiet -m "${subject%+note*}"
    done
    git rev-parse HEAD
}
# gate <expected status> <description> <head> [VAR=value...]
gate() {
    local expected=$1 description=$2 head=$3 status=0
    shift 3
    env BASE_SHA="$base" HEAD_SHA="$head" "$@" "$check" >/dev/null 2>&1 || status=$?
    [ "$status" = "$expected" ] && ok "$description" || fail "$description: exit $status, expected $expected"
}
echo "# check-pr.sh"
gate 1 "a feat commit without a note fails" "$(pull_request "feat(gui): shiny")"
gate 0 "the no-changeset label waives it" "$(pull_request "feat(gui): shiny")" NO_CHANGESET=true
gate 0 "a feat commit with a note passes" "$(pull_request "feat: shiny+note")"
gate 0 "a note in an earlier commit of the PR covers its fixes" "$(pull_request "feat: shiny+note" "fix: follow-up")"
gate 0 "internal commits need no note" "$(pull_request "chore: tidy" "refactor: split")"
gate 1 "a squash-merge title counts" "$(pull_request "wip")" PR_TITLE="fix: the actual fix"
gate 1 "breaking changes count" "$(pull_request "perf!: rewrite")"
pull_request "fix: x" >/dev/null
mkdir -p changes/1.12.0
printf 'type=fix\nen=E\nfr=F\n' >changes/1.12.0/late.properties
git add -A
git commit --quiet -m "fix: note in a released version"
gate 1 "a note outside changes/unreleased/ does not count" "$(git rev-parse HEAD)"

# ---------------------------------------------------------------------------------------------------------------------
# commit-msg hook
# ---------------------------------------------------------------------------------------------------------------------
echo "# commit-msg hook"
git init --quiet "$root/hook"
cd "$root/hook"
git config core.hooksPath "$hooks"
echo base >file.txt
git add -A
git commit --quiet -m "chore: base"
git update-ref refs/remotes/origin/main HEAD
commit() { # commit <expected status> <description> <subject>
    local status=0
    echo "$3" >>file.txt
    git add file.txt
    git commit --quiet -m "$3" >/dev/null 2>&1 || status=$?
    [ "$status" = "$1" ] && ok "$2" || fail "$2: exit $status, expected $1"
    git reset --quiet # unstage whatever a refused commit left
}
commit 1 "a fix without a note is refused" "fix: no note"
commit 0 "an internal commit is accepted" "chore: tidy"
commit 0 "a fixup of a feat is accepted" "fixup! feat: shiny"
mkdir -p changes/unreleased
printf 'type=fix\nen=E\nfr=F\n' >changes/unreleased/hooked.properties
git add changes/unreleased/hooked.properties
commit 0 "a fix staging its note is accepted" "fix: with its note"
commit 0 "a later fix of the same branch is covered" "fix: follow-up"

echo
if [ "$failures" -eq 0 ]; then
    echo "All release-note scenarios pass."
else
    echo "$failures release-note scenario(s) FAILED." >&2
    exit 1
fi
