#!/usr/bin/env bash
set -euo pipefail

version="${1:?version is required}"

# Mill reads these env vars automatically for SonatypeCentralPublishModule:
#   MILL_SONATYPE_USERNAME, MILL_SONATYPE_PASSWORD
#   MILL_PGP_PASSPHRASE, MILL_PGP_SECRET_BASE64
export MILL_SONATYPE_USERNAME="${SONATYPE_USERNAME:?SONATYPE_USERNAME is required}"
export MILL_SONATYPE_PASSWORD="${SONATYPE_PASSWORD:?SONATYPE_PASSWORD is required}"
export MILL_PGP_PASSPHRASE="${PGP_PASSPHRASE:?PGP_PASSPHRASE is required}"
export MILL_PGP_SECRET_BASE64="$(echo "$PGP_SECRET" | tr -d '\n\r ')"
export PUBLISH_VERSION="$version"

./mill -i __.publishSonatypeCentral
