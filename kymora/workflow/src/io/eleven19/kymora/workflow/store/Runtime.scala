package io.eleven19.kymora.workflow.store

import kyo.*

/** Runtime metadata embedded in a [[TaskRecord]] manifest.
  *
  *   - `os`   — operating-system identifier (e.g. `"darwin"`, `"linux"`).
  *   - `arch` — CPU architecture (e.g. `"aarch64"`, `"x86_64"`).
  *   - `jvm`  — optional JVM vendor + version string for JVM runs.
  *
  * Fields are all simple primitives, so `Schema` derivation works without
  * additional opaque-type givens.
  */
final case class Runtime(os: String, arch: String, jvm: Maybe[String])
    derives Schema, CanEqual
