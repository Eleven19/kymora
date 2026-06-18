package io.eleven19.kymora.workflow

import kyo.*

/** Codec-parameterised serialization typeclass for cached task outputs.
  *
  * The `Codec` is supplied at encode/decode time (provided by the engine from `Workflow.Runtime`), keeping `Cacheable`
  * instances codec-agnostic — a given `Cacheable[A]` is reusable across Json / Protobuf / future codecs. See spec §3.2.
  */
trait Cacheable[A]:
    def schema: Schema[A]

    def encode(a: A)(using codec: Codec): Chunk[Byte] =
        Chunk.from(schema.encode(a).toArray)

    def decode(bytes: Chunk[Byte])(using codec: Codec): Result[DecodeError, A] =
        schema.decode(Span.fromUnsafe(bytes.toArray)) match
            case Result.Success(v) => Result.succeed(v)
            case Result.Failure(e) => Result.fail(DecodeError.fromThrowable(e))
            case Result.Panic(t)   => Result.fail(DecodeError.fromThrowable(t))
end Cacheable

object Cacheable:

    /** Default derivation: delegate to `Schema[A]` using the codec in scope. */
    given fromSchema[A](using s: Schema[A]): Cacheable[A] =
        new Cacheable[A]:
            def schema: Schema[A] = s

    /** `derives Cacheable` entry point.
      *
      * {{{
      * final case class Report(name: String, total: Int)
      *     derives Schema, Cacheable
      * }}}
      */
    inline def derived[A](using s: Schema[A]): Cacheable[A] =
        fromSchema[A]

    def apply[A](using c: Cacheable[A]): Cacheable[A] = c
end Cacheable
