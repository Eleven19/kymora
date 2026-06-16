#!/usr/bin/env bash
set -euo pipefail

version="${1:?version is required}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
changelog_path="${CHANGELOG_PATH:-$repo_root/CHANGELOG.md}"
cliff_config="${CLIFF_CONFIG:-$repo_root/cliff.toml}"
release_notes_file="${RELEASE_NOTES_FILE:-$repo_root/out/release-notes-$version.md}"
release_tag="v$version"

if [[ ! -f "$changelog_path" ]]; then
    echo "CHANGELOG not found: $changelog_path" >&2
    exit 1
fi

if [[ ! -f "$cliff_config" ]]; then
    echo "git-cliff config not found: $cliff_config" >&2
    exit 1
fi

mkdir -p "$(dirname "$release_notes_file")"

curated_section="$(
    awk -v version="$version" '
        $0 ~ "^## \\[" version "\\] - " {
            in_section = 1
        }
        in_section {
            if (seen_heading && $0 ~ "^## \\[") {
                exit
            }
            print
            seen_heading = 1
        }
    ' "$changelog_path"
)"

if [[ -z "${curated_section//[$'\t\r\n ']}" ]]; then
    echo "Version section [$version] not found in $changelog_path" >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$release_tag" >/dev/null 2>&1; then
    generated_notes="$(git-cliff --config "$cliff_config" --current --tag "$release_tag" --strip all)"
else
    generated_notes="$(git-cliff --config "$cliff_config" --unreleased --strip all)"
fi

cat >"$release_notes_file" <<EOF
## Changelog

$curated_section

---

## Generated Notes

${generated_notes:-No additional generated notes.}
EOF

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'release_notes_file=%s\n' "$release_notes_file" >>"$GITHUB_OUTPUT"
fi

printf '%s\n' "$release_notes_file"
