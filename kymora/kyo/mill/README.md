# kymora-kyo-mill

`kymora-kyo-mill` provides Mill-native integrations for Kyo users. It mirrors
Kyo semantics where useful, but it does not port sbt concepts into Mill: users
add one Mill plugin dependency, mix traits into normal Mill modules, and run
ordinary Mill tasks.

```scala doctest:expect=skipped
//| - io.eleven19.kymora::kymora-kyo-mill::0.2.0-SNAPSHOT

import io.eleven19.kymora.kyo.mill.compat.*
import io.eleven19.kymora.kyo.mill.doctest.*
import io.eleven19.kymora.kyo.mill.ffi.*
import io.eleven19.kymora.kyo.mill.test.*
import io.eleven19.kymora.kyo.mill.wasm.*
import mill.*
import scalalib.*
```

The public API is organized by integration theme: `test`, `wasm`,
`doctest`, `compat`, and `ffi`. The root package only contains shared defaults.

## kyo-test

Use the platform-specific test trait for the module you are defining.

```scala doctest:expect=skipped
object app extends ScalaModule {
  def scalaVersion = "3.8.4"

  object test extends ScalaTests with KyoTestModule
}
```

For Scala.js, Scala.js WebAssembly, and Scala Native tests, use
`KyoTestJSModule`, `KyoTestWasmModule`, or `KyoTestNativeModule`.

## Scala.js WebAssembly

`KyoScalaJSWasmModule` configures a Scala.js module for WebAssembly output,
including the `_sjs1-wasm` platform suffix, ES modules, and Node.js runtime
flags expected by Kyo WASM artifacts.

```scala doctest:expect=skipped
object app extends KyoScalaJSWasmModule {
  def scalaVersion = "3.8.4"
}
```

WASM test execution requires Node.js 24 or newer.

## Doctest

`KyoDoctestModule` validates Markdown or Scala documentation snippets via
Kyo's doctest CLI. Validation commands do not rewrite tracked files.

```scala doctest:expect=skipped
object docs extends ScalaModule with KyoDoctestModule {
  def scalaVersion = "3.8.4"

  override def doctestSources =
    Task.Sources(moduleDir / "README.md")
}
```

Run:

```sh
./mill docs.doctest
./mill docs.doctestFresh
./mill docs.doctestClean
```

`doctestFormat` is reserved for a future Kyo formatter entrypoint; Kyo
1.0.0-RC4 exposes stable validation through the CLI, but not a stable
non-sbt formatting command.

## Compat

`KyoCompatModule` helps explicit Mill modules select Kyo compat artifacts.
Mill modules are statically declared, so consumers model backend/platform
combinations with ordinary modules or `Cross` modules instead of sbt
ProjectMatrix-style dynamic generation.

```scala doctest:expect=skipped
object zioCompat extends ScalaModule with KyoCompatModule {
  def scalaVersion = "3.8.4"
  def compatBackend = CompatBackend.Zio
  def compatPlatform = CompatPlatform.Jvm
}
```

Unsupported backend/platform combinations fail clearly when the compat axis is
evaluated.

## FFI

`KyoFfiModule` provides a Mill-native build integration for
[`kyo-ffi`](https://github.com/getkyo/kyo/tree/main/kyo-ffi). It keeps the Kyo
runtime and codegen semantics, but exposes them as ordinary Mill tasks and
module traits.

```scala doctest:expect=skipped
//| mvnDeps:
//| - io.eleven19.kymora::kymora-kyo-mill::0.2.0-SNAPSHOT
package build

import io.eleven19.kymora.kyo.mill.ffi.*
import io.eleven19.kymora.kyo.mill.test.*
import mill.*, scalalib.*

object app extends KyoFfiModule {
  def scalaVersion = "3.8.4"

  override def ffiLibraryId = Task { "math" }

  object test extends ScalaTests with KyoTestModule with KyoFfiTests {
    override def sources =
      Task.Sources(moduleDir / os.up / os.up / "test" / "src")

    override def ffiRuntimeJavaOptions =
      app.ffiRuntimeJavaOptions()
  }
}
```

The default layout is Mill-native:

```text
app/
  src/
    example/MathBindings.scala
  src/c/
    math.c
  test/src/
    example/MathBindingsTests.scala
```

Application code stays the Kyo FFI model:

```scala doctest:expect=skipped
package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait MathBindings extends Ffi:
  def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int

object MathBindings extends Ffi.Config(library = "math")
```

```c
int math_add(int a, int b) {
  return a + b;
}
```

For Scala Native vendored C, mark the Kyo binding as bundled so generated code
does not emit `@link("<library>")`; the Mill task copies C sources into managed
`resources/scala-native` for Scala Native to compile into the binary:

```scala doctest:expect=skipped
object MathBindings extends Ffi.Config(library = "math", nativeBundled = true)
```

### sbt-compatible layout

The sbt-compatible traits are an explicit layout compatibility layer, not an
sbt task/settings port. Use them when a downstream project already keeps Scala
and C sources under Maven-style directories.

```scala doctest:expect=skipped
object app extends KyoFfiSbtModule {
  def scalaVersion = "3.8.4"

  override def ffiLibraryId = Task { "math" }

  object test extends SbtTests with KyoTestModule with KyoFfiTests {
    override def ffiRuntimeJavaOptions =
      app.ffiRuntimeJavaOptions()
  }
}
```

```text
app/
  src/main/scala/
    example/MathBindings.scala
  src/main/c/
    math.c
  src/test/scala/
    example/MathBindingsTests.scala
```

The integration exposes these tasks:

- `ffiGenerate` compiles current Scala sources to scratch TASTy and generates
  Kyo FFI implementation sources.
- `ffiCompile` compiles declared C sources into platform-native shared
  libraries on JVM and Scala.js.
- `ffiPackage` packages JVM libraries under `META-INF/native/<os>-<arch>/` and
  JS libraries under `kyo-ffi/native/<os>-<arch>/`.
- `ffiNativeResources` and `ffiNativeLinkingOptions` support Scala Native by
  making C sources/resources and link options explicit.
- `ffiDumpCcCommand` prints the compiler command shape for diagnostics.
- `ffiNpmBundleTemplate` writes a minimal JS `package.json` with `koffi: ^2.7`.
- `ffiClean` removes generated FFI outputs.

Platform traits:

- `KyoFfiModule` targets JVM and discovers C sources from `src/c`.
- `KyoFfiJSModule` targets Scala.js and sets CommonJS output for Node/koffi
  compatibility while discovering C sources from `src/c`.
- `KyoFfiNativeModule` targets Scala Native and wires
  `ffiNativeLinkingOptions` into `nativeLinkingOptions` while discovering C
  sources from `src/c`.
- `KyoFfiWasmModule` fails clearly because Kyo FFI does not support Scala.js
  WebAssembly.
- `KyoFfiSbtModule`, `KyoFfiSbtJSModule`, `KyoFfiSbtNativeModule`, and
  `KyoFfiSbtWasmModule` provide the same platform semantics with explicit
  `src/main/c` source discovery for sbt/Maven-compatible layouts.

The integration fixtures cover realistic vendored-C usage across primitives,
strings passed into C, buffers, structs, callbacks, JVM shared-library loading,
Scala.js koffi loading, and Scala Native bundled C resources.

### Realistic library examples

The integration tests include complete downstream projects that double as
copyable examples.

`ffi-string-library` models a small `textkit` C library that accepts multiple
`String` values and returns primitive results:

```scala doctest:expect=skipped
trait TextKitBindings extends Ffi:
  def textkitCountWords(text: String)(using AllowUnsafe): Int
  def textkitHasPrefix(text: String, prefix: String)(using AllowUnsafe): Boolean
  def textkitSharedPrefixLength(left: String, right: String)(using AllowUnsafe): Int
  def textkitScoreTitle(title: String, keyword: String)(using AllowUnsafe): Double

object TextKitBindings extends Ffi.Config(library = "textkit")
```

```c
bool textkit_has_prefix(const char *text, const char *prefix) {
  return strncmp(text, prefix, strlen(prefix)) == 0;
}
```

`ffi-struct-library` models a `contacts` C library with nested structs, string
parameters, and a struct return containing a string field:

```scala doctest:expect=skipped
case class Address(zip: Int, cityCode: Int)
case class Contact(id: Int, score: Int, address: Address)
case class Badge(priority: Int, label: String)

trait ContactBindings extends Ffi:
  def contactsRouteCode(contact: Contact)(using AllowUnsafe): Int
  def contactsIsLocal(contact: Contact, city: String)(using AllowUnsafe): Boolean
  def contactsBadge(contact: Contact)(using AllowUnsafe): Badge

object ContactBindings extends Ffi.Config(library = "contacts")
```

```c
typedef struct {
  int32_t zip;
  int32_t city_code;
} address_t;

typedef struct {
  int32_t id;
  int32_t score;
  address_t address;
} contact_t;

int32_t contacts_badge(const contact_t *contact, const char **out_label) {
  bool local = contact->address.zip == 60606;
  *out_label = local ? "local-contact" : "remote-contact";
  return local ? 10 : 1;
}
```

The normative behavior spec lives in
[`docs/ffi.ears.md`](docs/ffi.ears.md), with requirement IDs mapped to unit and
integration test suites.

## Scope

This artifact currently covers kyo-test, Scala.js WebAssembly module defaults,
doctest validation, compat artifact selection, and Kyo FFI build integration.
The implementation is intentionally Mill-native: configuration is expressed as
module `def`s, generated files flow through Mill source/resource tasks, and
cleanup/template actions are explicit commands.
