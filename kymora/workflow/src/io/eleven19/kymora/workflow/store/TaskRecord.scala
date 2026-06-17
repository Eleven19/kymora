package io.eleven19.kymora.workflow.store

import io.eleven19.kymora.workflow.*
import kyo.*

/** Per-task manifest record.
  *
  * One JSON file per task, embedding the cached `value`, the canonical hash
  * fields, and the runtime metadata collected when the task was executed.
  *
  * Hash fields (`valueHash`, `inputsHash`, `bodyHash`) are [[Fingerprint]],
  * an opaque `String` alias. `bodyVersion` is [[TaskVersion]] (a real case
  * class). `createdAt` is `java.time.Instant`, for which `kyo-schema` ships a
  * built-in `instantSchema`.
  *
  * The companion supplies an explicit `Schema` given that summons each field
  * schema by name — this sidesteps a macro limitation in `kyo-schema`
  * 1.0.0-RC2 where `Schema.derived` on a case class containing opaque-type
  * fields can blow up at runtime with `SchemaNotSerializableException`.
  */
final case class TaskRecord[A](
    value: A,
    valueHash: Fingerprint,
    inputsHash: Fingerprint,
    bodyVersion: TaskVersion,
    bodyHash: Fingerprint,
    workflowVersion: String,
    scalaVersion: String,
    runtime: Runtime,
    createdAt: java.time.Instant,
) derives CanEqual

object TaskRecord:
  given schema[A](using Schema[A]): Schema[TaskRecord[A]] = Schema.derived
end TaskRecord
