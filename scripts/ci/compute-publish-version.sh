#!/usr/bin/env bash
set -euo pipefail

# Compute the publish version based on the CI context.
#
# Usage: compute-publish-version.sh <base-version>
#
# Environment variables (set by GitHub Actions):
#   GITHUB_EVENT_NAME  - "pull_request", "push", etc.
#   GITHUB_REF_NAME    - branch name or tag name
#   GITHUB_HEAD_REF    - PR source branch (only on pull_request events)
#   GITHUB_BASE_REF    - PR target branch (only on pull_request events)
#
# Output:
#   PR builds           → <base>-SNAPSHOT
#   Non-main branch     → <base>-<branch>-SNAPSHOT
#   Main branch         → <base>-SNAPSHOT
#   Tag push            → version from tag (no suffix)

base_version="${1:?base version is required}"
event_name="${GITHUB_EVENT_NAME:-local}"
ref_name="${GITHUB_REF_NAME:-}"
head_ref="${GITHUB_HEAD_REF:-}"

sanitize_branch() {
  # Replace characters not valid in Maven versions with hyphens
  echo "$1" | sed 's/[^a-zA-Z0-9._-]/-/g' | sed 's/--*/-/g' | sed 's/^-//;s/-$//'
}

case "$event_name" in
  pull_request)
    echo "${base_version}-SNAPSHOT"
    ;;
  push)
    if [[ "$ref_name" == v* ]]; then
      # Tag push — use exact version from tag
      echo "${ref_name#v}"
    elif [[ "$ref_name" == "main" ]]; then
      echo "${base_version}-SNAPSHOT"
    else
      branch_suffix="$(sanitize_branch "$ref_name")"
      echo "${base_version}-${branch_suffix}-SNAPSHOT"
    fi
    ;;
  *)
    # Local or unknown — default to SNAPSHOT
    echo "${base_version}-SNAPSHOT"
    ;;
esac
