#!/usr/bin/env bash
set -euo pipefail

release_tag="${RELEASE_TAG:?RELEASE_TAG is required}"
release_sha="${GITHUB_SHA:?GITHUB_SHA is required}"

if git ls-remote --exit-code --tags origin "refs/tags/$release_tag" >/dev/null 2>&1; then
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git tag "$release_tag" "$release_sha"
git push origin "$release_tag"
