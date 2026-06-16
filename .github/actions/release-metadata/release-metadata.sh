#!/usr/bin/env bash
set -euo pipefail

semver_pattern='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'

emit_output() {
  local key="$1"
  local value="$2"

  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$key" "$value" >>"$GITHUB_OUTPUT"
  fi

  printf '%s=%s\n' "$key" "$value"
}

event_name="${GITHUB_EVENT_NAME:-}"
ref_name="${GITHUB_REF_NAME:-}"
version_input="${INPUT_VERSION:-}"

case "$event_name" in
  workflow_dispatch)
    version="${version_input#v}"
    if [[ -z "$version" ]]; then
      echo "workflow_dispatch requires INPUT_VERSION" >&2
      exit 1
    fi
    ;;
  push)
    version="${ref_name#v}"
    if [[ -z "$ref_name" ]]; then
      echo "push events require GITHUB_REF_NAME" >&2
      exit 1
    fi
    ;;
  *)
    if [[ -n "$version_input" ]]; then
      version="${version_input#v}"
    elif [[ -n "$ref_name" ]]; then
      version="${ref_name#v}"
    else
      echo "Unable to determine release version from environment" >&2
      exit 1
    fi
    ;;
esac

if [[ ! "$version" =~ $semver_pattern ]]; then
  echo "Invalid semantic version: $version" >&2
  exit 1
fi

tag="v$version"

if [[ "$version" == *-* ]]; then
  prerelease=true
else
  prerelease=false
fi

emit_output version "$version"
emit_output tag "$tag"
emit_output prerelease "$prerelease"
emit_output release_name "$tag"
