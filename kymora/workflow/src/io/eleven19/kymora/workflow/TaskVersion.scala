package io.eleven19.kymora.workflow

import kyo.*

/** Semantic version for a task body.
  *
  * Bump the version when a task's implementation changes in a way that should
  * invalidate previous cached results even if its dependencies did not change.
  */
final case class TaskVersion(major: Int, minor: Int, patch: Int) derives CanEqual:
  require(major >= 0 && minor >= 0 && patch >= 0, "TaskVersion components must be >= 0")
  def render: String         = s"$major.$minor.$patch"
  def bumpMajor: TaskVersion = TaskVersion(major + 1, 0, 0)
  def bumpMinor: TaskVersion = TaskVersion(major, minor + 1, 0)
  def bumpPatch: TaskVersion = TaskVersion(major, minor, patch + 1)
end TaskVersion

object TaskVersion:
  /** Default initial task version. */
  val v1: TaskVersion                                       = TaskVersion(1, 0, 0)
  /** Builds a version from numeric components. */
  def of(major: Int, minor: Int, patch: Int): TaskVersion   = TaskVersion(major, minor, patch)

  /** Compile-time checked version literal such as `TaskVersion("1.2.3")`. */
  inline def apply(inline literal: String): TaskVersion =
    ${ io.eleven19.kymora.workflow.macros.TaskVersionMacros.literal('literal) }

  /** Runtime parser for user-provided semantic versions. */
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
          case Result.Success(v)              => v
          // kyo-schema's `readFn: Reader => A` is total — there is no
          // failure channel to thread a Result through. The structured
          // ParseError variant doubles as a RuntimeException so callers
          // can `catch ParseError =>` or pattern-match on the cause.
          case Result.Failure(err: ParseError) => throw err
          case _                               => throw ParseError.MalformedTaskVersion(s)
    )
end TaskVersion
