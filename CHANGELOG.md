# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

At release time, add a `## [<version>] - <YYYY-MM-DD>` section here; the release
workflow (`build-release-notes.sh`) extracts the matching section for the
GitHub release body.

## [Unreleased]

## [0.2.0] - 2026-06-19

### Added

- Workflow telemetry and inspection APIs.
- Kymora docs/website module and release-time docs validation.
- Renovate automation for dependency update management.

### Changed

- Workflow engine now supports persistent workspaces and cross-run cache value
  persistence.
- Workflow execution now enforces `continueOnError`.

### Fixed

- Removed HostVfs Windows path workaround.

## [0.1.0] - 2026-06-19

### Added

- Initial cross-platform Kymora modules: `kymora-vfs`, `kymora-workflow`, and
  `kymora-workflow-testkit` on JVM, Scala.js, Scala.js WASM, and Scala Native.
- JVM-only `kymora-kyo-mill` plugin for downstream Kyo users building with Mill.
- CI, snapshot publish, Maven Central publish, and GitHub release workflows.
