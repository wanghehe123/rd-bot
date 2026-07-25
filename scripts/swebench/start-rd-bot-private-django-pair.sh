#!/usr/bin/env bash
# Start the local RD-Bot backend with the two private Django SWE-bench snapshots allowlisted.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

export SPRING_APPLICATION_JSON='{"rd":{"executor":{"docker":{"security":{"repositoryUrls":["https://github.com/wanghehe123/swebench-lite-django-11019.git","https://github.com/wanghehe123/swebench-lite-django-11001.git"],"repositories":["wanghehe123/swebench-lite-django-11019","wanghehe123/swebench-lite-django-11001"],"baseBranches":["swebench/django-11019-rd-bot","swebench/django-11001-rd-bot"],"workBranches":["repair/*","requirement/*"]}}}}}'

# GitHub HTTPS transfers are unreliable behind the local HTTP proxy. Keep the
# canonical GitHub remote in every workspace, but source the two immutable
# private SWE-bench snapshots from their verified local mirrors.
export GIT_CONFIG_COUNT=2
export GIT_CONFIG_KEY_0='url.file:///Volumes/WishDisk/codes/swe/private-repositories/swebench-lite-django-11019/.insteadOf'
export GIT_CONFIG_VALUE_0='https://github.com/wanghehe123/swebench-lite-django-11019.git'
export GIT_CONFIG_KEY_1='url.file:///Volumes/WishDisk/codes/swe/private-repositories/swebench-lite-django-11001-source.git.insteadOf'
export GIT_CONFIG_VALUE_1='https://github.com/wanghehe123/swebench-lite-django-11001.git'

exec ./mvnw -pl bootstrap spring-boot:run
