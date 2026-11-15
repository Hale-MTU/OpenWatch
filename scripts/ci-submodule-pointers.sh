#!/usr/bin/env bash
# Fails when a submodule points at a commit that is not on its repository's
# default branch.
#
# A submodule is a commit id, and nothing stops that id from naming a commit
# that only a pull request branch holds. The build works until the branch is
# deleted or squashed, and then a fresh clone cannot check the submodule out.
# So the library's pull request merges first, and the app's pointer names a
# commit its default branch reaches.
#
#   scripts/ci-submodule-pointers.sh
#
# It reads the pointers of HEAD and asks each submodule's remote, so it needs
# the network and no checked-out submodule.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
[ -f .gitmodules ] || { echo "submodules: none"; exit 0; }

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
failed=0

while read -r key path; do
  name="${key#submodule.}"
  name="${name%.path}"
  url="$(git config -f .gitmodules --get "submodule.$name.url")"
  pointer="$(git ls-tree HEAD -- "$path" | awk '$2 == "commit" { print $3 }')"
  if [ -z "$pointer" ]; then
    echo "submodules: $path is in .gitmodules but HEAD has no pointer for it"
    failed=1
    continue
  fi
  repo="$scratch/$name"
  git init -q --bare "$repo"
  # History only: the check needs commits, not files.
  if ! git -C "$repo" fetch -q --filter=blob:none "$url" HEAD; then
    echo "submodules: could not reach $url"
    failed=1
    continue
  fi
  if git -C "$repo" merge-base --is-ancestor "$pointer" FETCH_HEAD 2>/dev/null; then
    echo "submodules: $path ${pointer:0:8} is on the default branch of $url"
  else
    echo "submodules: $path points at ${pointer:0:8}, which the default branch of $url does not reach."
    echo "  Merge the library's pull request first, then point $path at a commit on its default branch."
    failed=1
  fi
done < <(git config -f .gitmodules --get-regexp '^submodule\..*\.path$')

exit "$failed"
