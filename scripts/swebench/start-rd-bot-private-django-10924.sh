#!/usr/bin/env bash
# Start local RD-Bot backend for private SWE-bench django__django-10924 snapshot.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"
exec env \
  RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY_URL="https://github.com/wanghehe123/swebench-lite-django-10924.git" \
  RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY="wanghehe123/swebench-lite-django-10924" \
  RD_EXECUTOR_DOCKER_ALLOWED_BASE_BRANCH="swebench/django-10924-rd-bot" \
  RD_EXECUTOR_DOCKER_ALLOWED_WORK_BRANCH="requirement/*" \
  RD_EXECUTOR_DOCKER_ALLOWED_REQUIREMENT_BRANCH="requirement/*" \
  GIT_CONFIG_COUNT=1 \
  GIT_CONFIG_KEY_0='url.file:///Users/wish233/Documents/RD-Bot/private-repos/swebench-lite-django__django-10924/.insteadOf' \
  GIT_CONFIG_VALUE_0='https://github.com/wanghehe123/swebench-lite-django-10924.git' \
  RD_QA_EXECUTION_TIMEOUT_MILLIS=2400000 \
  JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/logs/java-tmp" \
  ./mvnw -pl bootstrap spring-boot:run
