#!/usr/bin/env bash
# Start the local RD-Bot backend with the narrow allowlist required for the
# private SWE-bench django__django-11019 snapshot.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

exec env \
  RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY_URL="https://github.com/wanghehe123/swebench-lite-django-11019.git" \
  RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY="wanghehe123/swebench-lite-django-11019" \
  RD_EXECUTOR_DOCKER_ALLOWED_BASE_BRANCH="swebench/django-11019-rd-bot" \
  RD_EXECUTOR_DOCKER_ALLOWED_WORK_BRANCH="requirement/*" \
  RD_EXECUTOR_DOCKER_ALLOWED_REQUIREMENT_BRANCH="requirement/*" \
  GIT_CONFIG_COUNT=1 \
  GIT_CONFIG_KEY_0='url.file:///Volumes/WishDisk/codes/swe/private-repositories/swebench-lite-django-11019/.insteadOf' \
  GIT_CONFIG_VALUE_0='https://github.com/wanghehe123/swebench-lite-django-11019.git' \
  ./mvnw -pl bootstrap spring-boot:run
