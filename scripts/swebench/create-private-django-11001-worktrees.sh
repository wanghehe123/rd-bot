#!/usr/bin/env bash
# Create the two isolated worktrees from the private django__django-11001 snapshot.
set -euo pipefail

PRIVATE_REPOSITORY="https://github.com/wanghehe123/swebench-lite-django-11001.git"
MASTER_REPOSITORY="/Volumes/WishDisk/codes/swe/private-repositories/swebench-lite-django-11001-source.git"
LOCAL_SEED_REPOSITORY="/private/tmp/swebench-lite-django-11001-snapshot"
EXPECTED_COMMIT="76381be0e7352ae3d06164b733c0643999dcd489"
EXPECTED_TREE="1b6d22c787dafd5cd06615511a724216fad221bf"

RD_BOT_WORKTREE="/Volumes/WishDisk/codes/swe/private-worktrees/rd-bot/django__django-11001"
CLAUDE_CODE_WORKTREE="/Volumes/WishDisk/codes/swe/private-worktrees/claude-code/django__django-11001"

if [[ ! -d "$MASTER_REPOSITORY" ]]; then
  mkdir -p "$(dirname "$MASTER_REPOSITORY")"
  if [[ -d "$LOCAL_SEED_REPOSITORY/.git" ]] \
    && git -C "$LOCAL_SEED_REPOSITORY" cat-file -e "${EXPECTED_COMMIT}^{commit}"; then
    # The seed avoids a flaky large HTTPS clone; its tree was verified against SWE-bench.
    git clone --bare --local --no-hardlinks "$LOCAL_SEED_REPOSITORY" "$MASTER_REPOSITORY"
  else
    git clone --bare "$PRIVATE_REPOSITORY" "$MASTER_REPOSITORY"
  fi
fi

git -C "$MASTER_REPOSITORY" cat-file -e "${EXPECTED_COMMIT}^{commit}" \
  || { echo "private master repository is incomplete: $MASTER_REPOSITORY" >&2; exit 1; }
git -C "$MASTER_REPOSITORY" show-ref --verify --quiet refs/heads/swebench/django-11001-rd-bot \
  || { echo "RD-Bot branch is missing from the private master repository" >&2; exit 1; }
git -C "$MASTER_REPOSITORY" show-ref --verify --quiet refs/heads/swebench/django-11001-claude-code \
  || { echo "Claude Code branch is missing from the private master repository" >&2; exit 1; }
git -C "$MASTER_REPOSITORY" remote set-url origin "$PRIVATE_REPOSITORY"

if [[ ! -e "$RD_BOT_WORKTREE" ]]; then
  mkdir -p "$(dirname "$RD_BOT_WORKTREE")"
  git -C "$MASTER_REPOSITORY" worktree add "$RD_BOT_WORKTREE" swebench/django-11001-rd-bot
fi

if [[ ! -e "$CLAUDE_CODE_WORKTREE" ]]; then
  mkdir -p "$(dirname "$CLAUDE_CODE_WORKTREE")"
  git -C "$MASTER_REPOSITORY" worktree add "$CLAUDE_CODE_WORKTREE" swebench/django-11001-claude-code
fi

for worktree in "$RD_BOT_WORKTREE" "$CLAUDE_CODE_WORKTREE"; do
  [[ "$(git -C "$worktree" rev-parse HEAD)" == "$EXPECTED_COMMIT" ]] \
    || { echo "unexpected snapshot commit in $worktree" >&2; exit 1; }
  [[ "$(git -C "$worktree" rev-parse 'HEAD^{tree}')" == "$EXPECTED_TREE" ]] \
    || { echo "unexpected source tree in $worktree" >&2; exit 1; }
  [[ -z "$(git -C "$worktree" status --porcelain --untracked-files=all)" ]] \
    || { echo "worktree is not clean: $worktree" >&2; exit 1; }
done

echo "Created isolated private worktrees for django__django-11001."
