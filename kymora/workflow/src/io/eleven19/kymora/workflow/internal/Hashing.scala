package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

private[workflow] object Hashing:

  /** A dep fingerprint pair: (dep's TaskId, dep's valueHash) — used by
    * inputsHash so a downstream task invalidates when an upstream's value
    * changes.
    */
  final case class DepFingerprint(id: TaskId, fingerprint: Fingerprint)

  /** Engine-constant body fingerprint for Source nodes. */
  val SourceBodyHash: Fingerprint =
    Fingerprint.ofBytes(Chunk.from("source:v1".getBytes))

  /** Engine-constant body fingerprint for Input nodes. */
  val InputBodyHash: Fingerprint =
    Fingerprint.ofBytes(Chunk.from("input:v1".getBytes))

  /** bodyHash(id, version) = blake3(id || ":" || version.render). */
  def bodyHash(id: TaskId, version: TaskVersion): Fingerprint =
    Fingerprint.ofBytes(Chunk.from(s"${id.value}:${version.render}".getBytes))

  /** inputsHash(task) = blake3(
    *   "kymora-workflow/v1" || task.id || bodyHash ||
    *   join("|", deps.sortedBy(_.id.value).map(d => d.id || ":" || d.fingerprint))
    * )
    *
    * Deps are sorted by id so declaration-order permutations don't change
    * the hash. The body executes deps in declaration order; this is
    * separate.
    */
  def inputsHash(taskId: TaskId, bodyHash: Fingerprint, deps: Chunk[DepFingerprint]): Fingerprint =
    val sorted   = deps.toVector.sortBy(_.id.value)
    val depsPart = sorted.map(d => s"${d.id.value}:${d.fingerprint.value}").mkString("|")
    val raw      = s"kymora-workflow/v1|${taskId.value}|${bodyHash.value}|$depsPart"
    Fingerprint.ofBytes(Chunk.from(raw.getBytes))

end Hashing
