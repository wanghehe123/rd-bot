#!/usr/bin/env bash
# Start local RD-Bot backend with an owner-level execution allowlist.
# Defaults target org wanghehe123 (override with RD_OWNER_GITHUB_ORG or the
# RD_EXECUTOR_DOCKER_ALLOWED_* env vars). Not required for OSS contributors.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

owner_org="${RD_OWNER_GITHUB_ORG:-wanghehe123}"
# Example defaults (override any of these before invoking this script):
#   RD_OWNER_GITHUB_ORG=your-org
#   RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY_URL=https://github.com/your-org/*
#   RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY=your-org/*
allowed_repo_url="${RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY_URL:-https://github.com/${owner_org}/*}"
allowed_repo="${RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY:-${owner_org}/*}"
allowed_base_branch="${RD_EXECUTOR_DOCKER_ALLOWED_BASE_BRANCH:-main,master,swebench/*}"
allowed_work_branch="${RD_EXECUTOR_DOCKER_ALLOWED_WORK_BRANCH:-requirement/*,repair/*}"
allowed_requirement_branch="${RD_EXECUTOR_DOCKER_ALLOWED_REQUIREMENT_BRANCH:-requirement/*}"

env_args=(
  "RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY_URL=${allowed_repo_url}"
  "RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY=${allowed_repo}"
  "RD_EXECUTOR_DOCKER_ALLOWED_BASE_BRANCH=${allowed_base_branch}"
  "RD_EXECUTOR_DOCKER_ALLOWED_WORK_BRANCH=${allowed_work_branch}"
  "RD_EXECUTOR_DOCKER_ALLOWED_REQUIREMENT_BRANCH=${allowed_requirement_branch}"
  "RD_EXECUTOR_AGENT_RUNTIME_ENABLED=true"
  "RD_QA_EXECUTION_TIMEOUT_MILLIS=2400000"
  "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=${repo_root}/logs/java-tmp"
)

# Optional: map a private-repos checkout to a GitHub URL when the directory exists.
private_swebench_dir="${RD_OWNER_PRIVATE_SWEBENCH_DIR:-${repo_root}/private-repos/swebench-lite-django__django-10924}"
private_swebench_url="${RD_OWNER_PRIVATE_SWEBENCH_URL:-https://github.com/${owner_org}/swebench-lite-django-10924.git}"
if [[ -d "${private_swebench_dir}" ]]; then
  env_args+=(
    "GIT_CONFIG_COUNT=1"
    "GIT_CONFIG_KEY_0=url.file://${private_swebench_dir}/.insteadOf"
    "GIT_CONFIG_VALUE_0=${private_swebench_url}"
  )
fi

exec env "${env_args[@]}" ./mvnw -pl bootstrap spring-boot:run
