# Kyo Mill FFI Requirements

This document is the normative behavior checklist for the `kymora-kyo-mill`
FFI integration. Requirement names are mirrored by test names or suite sections
so behavior can be traced from docs to executable coverage.

## Generation

- **FFI-GEN-001**: When `ffiGenerate` runs, it shall compile the current Scala
  sources to scratch TASTy before the main compile so generated impls are
  available on the first compile.
- **FFI-GEN-002**: When generated impls are produced, they shall be returned as
  managed sources and compiled with the user module.
- **FFI-GEN-003**: When a binding trait is deleted or renamed, stale generated
  `*Impl.scala` files shall be removed.
- **FFI-GEN-004**: When strict discovery is enabled and no FFI traits are found,
  generation shall fail clearly.
- **FFI-GEN-005**: When Kyo codegen emits warnings, non-strict modes shall log
  them and strict modes shall fail.

## Configuration

- **FFI-CONFIG-001**: When no FFI settings are overridden, defaults shall match
  Kyo's plugin defaults for library id, compiler, and flags.
- **FFI-CONFIG-002**: When `ffiLibraries` is non-empty, it shall take precedence
  over single-library settings.
- **FFI-CONFIG-003**: When generated bindings reference an undeclared library id,
  generation shall fail unless the id is listed in `ffiSystemLibraries`.
- **FFI-CONFIG-004**: When runtime options are requested, scratch size and
  extraction directory shall become JVM system properties.
- **FFI-CONFIG-005**: When a module mixes a Mill-native FFI trait, C source
  discovery shall default to `src/c`.
- **FFI-CONFIG-006**: When a module mixes an explicit sbt-compatible FFI trait,
  C source discovery shall default to `src/main/c`.
- **FFI-CONFIG-007**: When a downstream project needs sbt/Maven source layout,
  it shall opt into `KyoFfiSbt*` traits rather than changing Mill-native
  defaults.

## Compilation And Packaging

- **FFI-COMPILE-001**: When compiling for JVM or JS, `ffiCompile` shall produce
  a platform-native shared library named with library id, OS, and arch.
- **FFI-COMPILE-002**: When compiling for Native, `ffiCompile` shall produce no
  shared library.
- **FFI-COMPILE-003**: When C headers and include directories are configured,
  compiler commands shall include them as include flags.
- **FFI-COMPILE-004**: When static linking is enabled, compiler commands shall
  statically fold only configured libraries and shall not emit a bare `-static`.
- **FFI-PACKAGE-001**: When packaging JVM artifacts, libraries shall be copied
  to `META-INF/native/<os>-<arch>/`.
- **FFI-PACKAGE-002**: When packaging JS artifacts, libraries shall be copied
  to `kyo-ffi/native/<os>-<arch>/`.
- **FFI-PACKAGE-003**: When packaging Native artifacts, packaging shall be a
  no-op.

## Native, Tasks, And End-To-End

- **FFI-NATIVE-001**: When compiling a Native FFI module, C sources shall be
  copied into managed `scala-native` resources.
- **FFI-NATIVE-002**: When Native link libraries are configured, the module
  shall expose corresponding `nativeLinkingOptions`.
- **FFI-TASKS-001**: When `ffiClean` runs, generated sources, compiled
  libraries, and managed native resources shall be removed.
- **FFI-TASKS-002**: When `ffiDumpCcCommand` runs, it shall report compiler
  commands without executing them.
- **FFI-TASKS-003**: When `ffiNpmBundleTemplate` runs, it shall create a
  minimal `package.json` that pins `koffi` to Kyo's supported range.
- **FFI-TASKS-004**: When FFI is requested for WASM, the module shall fail with
  a clear unsupported-platform message.
- **FFI-E2E-001**: When a JVM downstream module defines a binding trait and C
  source, it shall generate, compile, package, load, and call the binding.
- **FFI-E2E-002**: When a JS downstream module defines a binding trait and C
  source, it shall generate, compile, package, load, and call the binding under
  Node with koffi.
- **FFI-E2E-003**: When a Native downstream module defines a binding trait and C
  source with `nativeBundled = true`, it shall copy the C source into managed
  `scala-native` resources, link it into the binary, and call the binding.
- **FFI-E2E-004**: When vendored C bindings cover primitive numeric values,
  booleans, and strings passed into C, the generated JVM binding shall call the
  C implementation successfully.
- **FFI-E2E-005**: When vendored C bindings use Kyo FFI buffers, the generated
  binding shall support reading and mutating buffer contents.
- **FFI-E2E-006**: When vendored C bindings use nested structs, packed structs,
  multi-value returns, and struct string fields, the generated binding shall
  marshal those shapes successfully.
- **FFI-E2E-007**: When vendored C bindings use transient callbacks, buffer
  callbacks, and retained callbacks guarded by `Ffi.Guard`, the generated
  binding shall call those callback shapes successfully.
- **FFI-E2E-008**: When a realistic text utility library accepts multiple
  `String` parameters, the generated binding shall marshal those strings and
  return primitive C results successfully.
- **FFI-E2E-009**: When a realistic contact library accepts nested structs,
  accepts a string parameter, and returns a struct containing a string field,
  the generated binding shall marshal those values successfully.

## Traceability

- `PublicApiTests` covers public FFI traits and defaults.
- `FfiLibraryTests`, `FfiLibrarySortTests`, `FfiCCompilerTests`,
  `FfiPackagerTests`, `FfiNpmBundleTemplateTests`, and
  `FfiCodegenBridgeTests` cover helper behavior.
- `KyoMillIntegrationTests` FFI scenarios cover Mill task wiring, unsupported
  WASM behavior, JS npm-template generation, compiler command diagnostics,
  Mill-native layout defaults, explicit sbt-compatible layout defaults, JVM
  end-to-end loading, Scala.js koffi loading, Scala Native bundled resources,
  primitive/string bindings, realistic string-library usage, realistic
  struct-library usage with string parameters and string return fields,
  buffers, structs, and callbacks.
