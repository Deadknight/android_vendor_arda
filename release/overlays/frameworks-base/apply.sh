#!/bin/bash
set -Eeuo pipefail

ROOT="${1:-$(pwd)}"
REPO="$ROOT/frameworks/base"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "$HERE/overlay.meta"

[[ -d "$REPO/.git" || -f "$REPO/.git" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_REPO_MISSING"
  exit 2
}

[[ "$(git -C "$REPO" rev-parse HEAD)" == "$BASE_PARENT" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_PARENT_MISMATCH"
  exit 2
}

[[ -z "$(git -C "$REPO" status --porcelain=v1 --untracked-files=all)" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_NOT_CLEAN_BEFORE_OVERLAY"
  exit 2
}

actual_patch_sha="$(sha256sum "$HERE/0001-arda-frameworks-base.patch" | awk '{print $1}')"
[[ "$actual_patch_sha" == "$PATCH_SHA256" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_PATCH_SHA_MISMATCH"
  exit 2
}

git -C "$REPO" apply --check "$HERE/0001-arda-frameworks-base.patch"
git -C "$REPO" apply "$HERE/0001-arda-frameworks-base.patch"
git -C "$REPO" add -A

tree="$(git -C "$REPO" write-tree)"
[[ "$tree" == "$EXPECTED_TREE" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_OVERLAY_TREE_MISMATCH expected=$EXPECTED_TREE actual=$tree"
  exit 2
}

export GIT_AUTHOR_NAME="$AUTHOR_NAME"
export GIT_AUTHOR_EMAIL="$AUTHOR_EMAIL"
export GIT_COMMITTER_NAME="$AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$AUTHOR_EMAIL"
export GIT_AUTHOR_DATE="$AUTHOR_DATE"
export GIT_COMMITTER_DATE="$COMMITTER_DATE"

commit="$(
  printf '%s\n' "$COMMIT_MESSAGE" |
  git -C "$REPO" commit-tree "$tree" -p "$BASE_PARENT"
)"

[[ "$commit" == "$EXPECTED_COMMIT" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_COMMIT_TREE_MISMATCH expected=$EXPECTED_COMMIT actual=$commit"
  exit 2
}

# Hook-free: move the checkout to the exact deterministic commit object.
git -C "$REPO" reset --soft "$commit"

[[ "$(git -C "$REPO" rev-parse HEAD)" == "$EXPECTED_COMMIT" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_OVERLAY_HEAD_MISMATCH"
  exit 2
}

[[ -z "$(git -C "$REPO" status --porcelain=v1 --untracked-files=all)" ]] || {
  echo "FAIL=FRAMEWORKS_BASE_NOT_CLEAN_AFTER_OVERLAY"
  exit 2
}

echo "FRAMEWORKS_BASE_OVERLAY_APPLY=PASS"
