#!/usr/bin/env bash
set -euo pipefail

version="${1:?git-cliff version is required}"

default_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os:$arch" in
        Linux:x86_64)
            printf '%s\n' "x86_64-unknown-linux-gnu"
            ;;
        Linux:aarch64 | Linux:arm64)
            printf '%s\n' "aarch64-unknown-linux-gnu"
            ;;
        Darwin:x86_64)
            printf '%s\n' "x86_64-apple-darwin"
            ;;
        Darwin:arm64 | Darwin:aarch64)
            printf '%s\n' "aarch64-apple-darwin"
            ;;
        *)
            echo "Unsupported platform for git-cliff install: $os/$arch" >&2
            exit 1
            ;;
    esac
}

platform="${2:-$(default_platform)}"
asset="git-cliff-${version}-${platform}.tar.gz"
download_url="https://github.com/orhun/git-cliff/releases/download/v${version}/${asset}"
install_dir="${RUNNER_TEMP:-/tmp}/git-cliff-${version}"

mkdir -p "$install_dir"
curl -LsSf "$download_url" | tar -xz -C "$install_dir"

binary_dir="$(find "$install_dir" -type f -name git-cliff -printf '%h\n' | head -n 1)"
if [[ -z "$binary_dir" ]]; then
    echo "Unable to locate git-cliff binary under $install_dir" >&2
    exit 1
fi

if [[ -n "${GITHUB_PATH:-}" ]]; then
    printf '%s\n' "$binary_dir" >>"$GITHUB_PATH"
else
    export PATH="$binary_dir:$PATH"
fi

"$binary_dir/git-cliff" --version
