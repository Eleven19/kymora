package io.eleven19.kymora.kyo.mill.ffi

/** Target platform for Kyo FFI code generation and native artifact handling. */
enum FfiTargetPlatform derives CanEqual, upickle.default.ReadWriter:
    case Jvm, Js, Native, Wasm

    def codegenName: String = this match
        case Jvm    => "JVM"
        case Js     => "JS"
        case Native => "Native"
        case Wasm   => unsupported().getMessage

    def unsupported(): UnsupportedOperationException =
        new UnsupportedOperationException("Kyo FFI is not supported on Scala.js WASM")
