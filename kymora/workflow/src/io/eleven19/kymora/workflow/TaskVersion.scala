package io.eleven19.kymora.workflow

import kyo.*

final case class TaskVersion(major: Int, minor: Int, patch: Int) derives CanEqual:
  require(major >= 0 && minor >= 0 && patch >= 0, "TaskVersion components must be >= 0")
  def render: String         = s"$major.$minor.$patch"
  def bumpMajor: TaskVersion = TaskVersion(major + 1, 0, 0)
  def bumpMinor: TaskVersion = TaskVersion(major, minor + 1, 0)
  def bumpPatch: TaskVersion = TaskVersion(major, minor, patch + 1)
end TaskVersion

object TaskVersion:
  val v1: TaskVersion                                       = TaskVersion(1, 0, 0)
  def of(major: Int, minor: Int, patch: Int): TaskVersion   = TaskVersion(major, minor, patch)

  inline def apply(inline literal: String): TaskVersion =
    ${ io.eleven19.kymora.workflow.macros.TaskVersionMacros.literal('literal) }

  def parse(s: String): Result[ParseError, TaskVersion] =
    s.split('.') match
      case Array(a, b, c) =>
        (a.toIntOption, b.toIntOption, c.toIntOption) match
          case (Some(majorN), Some(minorN), Some(patchN))
              if majorN >= 0 && minorN >= 0 && patchN >= 0 =>
            Result.succeed(TaskVersion(majorN, minorN, patchN))
          case _ => Result.fail(ParseError.MalformedTaskVersion(s))
      case _ => Result.fail(ParseError.MalformedTaskVersion(s))

  given schema: Schema[TaskVersion] =
    Schema.init[TaskVersion](
      writeFn = (v, w) => w.string(v.render),
      readFn = r =>
        val s = r.string()
        parse(s) match
          case Result.Success(v) => v
          // kyo-schema's `readFn: Reader => A` is total — there is no
          // failure channel to thread a Result through. Malformed input
          // throws; the surrounding decode invocation surfaces it.
          case _ => throw new IllegalArgumentException(s"Malformed TaskVersion: $s")
    )
end TaskVersion
