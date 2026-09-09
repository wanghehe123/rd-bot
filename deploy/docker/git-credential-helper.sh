#!/bin/sh
# Git credential helper for the RD-Bot backend container (see git-credentials.conf).
# Reads GITHUB_PAT from the container environment; contains no secret itself.
[ "$1" = "get" ] || exit 0
printf 'username=x-access-token\n'
printf 'password=%s\n' "$GITHUB_PAT"
