package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Scheduler
import io.eleven19.kymora.workflow.internal.Validation
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.*
import kyo.*

// `kyo.*` re-exports its own `Command` type which would shadow the package-
// level `Command` alias under the wildcard import. Pin our alias back so
// `runCli`'s `cmd: Command[A]` parameter resolves to the workflow Command.
import io.eleven19.kymora.workflow.Command

object Workflow:
  /** Definition-scope helper.
    *
    * Compile-time validated literal prefix. Threads a using TaskScope so all
    * Task.init / Source.init / Input.init / Command.init invocations inside
    * `body` see the qualified prefix.
    *
    * Resource scopes (cache lock, reporter session, etc.) use Kyo's Scope
    * effect separately — see Workflow.run in Phase 11.
    */
  inline def scope[A](inline prefix: String)(body: TaskScope ?=> A)(using
      outer: TaskScope,
  ): A =
    body(using outer.qualify(TaskScope(prefix).value))

  /** Runtime-parsed prefix variant. Returns Result.fail on invalid input. */
  def scopeWith[A](prefix: String)(body: TaskScope ?=> A)(using
      outer: TaskScope,
  ): Result[Validation.Reason, A] =
    TaskScope.parse(prefix).map(s => body(using outer.qualify(s.value)))

  /** Runtime configuration. Injected via `Env[Workflow.Config]`.
    *
    * Carries the engine's pluggable seams: cache store, blob codec, scheduler
    * fan-out, reporter sink, and a handful of run-level flags (bypass /
    * read-only / no-cache / continue-on-error / verify-dest). The default
    * `Clock` is the ambient `Clock.live`; explicit clock injection is left to
    * later phases if needed.
    */
  final case class Config(
      store: CacheStore,
      codec: Codec,
      parallelism: Int,
      reporter: Reporter,
      bypass: Set[TaskId] = Set.empty,
      readOnly: Boolean = false,
      noCache: Boolean = false,
      continueOnError: Boolean = false,
      verifyDest: Boolean = false,
  )

  object Config:
    /** A sensible default `Config`: in-memory VFS rooted at `cache/`,
      * `Json()` blob codec, parallelism = max(2, cores - 1), and the
      * `ConsoleReporter` sink. */
    def default(using Frame): Config < (Async & Abort[StoreError]) =
      for
        vfs   <- Vfs.inMemory.init
        root   = VPath("cache")
        store <- VfsDirStore.init(root, vfs)
      yield Config(
        store       = store,
        codec       = Json(),
        parallelism = math.max(2, java.lang.Runtime.getRuntime.availableProcessors - 1),
        reporter    = ConsoleReporter,
      )
  end Config

  /** Execute a single goal task under the ambient [[Workflow.Config]].
    *
    * Implementation lives in [[internal.Scheduler]]. The current slice is a
    * sequential, in-process scheduler that handles `Source` / `Input` /
    * `Task.Cached`. `Task.Persistent` and `Task.Command` paths surface a
    * `WorkflowError.TaskFailed` describing the gap. Parallelism (Task 47),
    * real `TaskRecord[A]` round-tripping (Tasks 48-49), and persistent /
    * command kinds (Tasks 45-46) arrive in subsequent plan tasks.
    */
  def run[A](goal: Task[A])(using
      Frame,
  ): A < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
    Scheduler.execute(goal)

  /** Execute multiple goal tasks in declaration order under the ambient
    * [[Workflow.Config]] and return their materialised values.
    */
  def runAll[A](goals: Task[A]*)(using
      Frame,
  ): Chunk[A] < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
    Kyo.foreach(Chunk.from(goals))(g => Scheduler.execute(g))

  /** Wipe the entire cache. */
  def purge(): Unit < (Async & Env[Workflow.Config] & Abort[StoreError]) =
    for
      cfg <- Env.get[Workflow.Config]
      _   <- cfg.store.purge()
    yield ()

  /** Remove cache entries whose key matches the given prefix. */
  def clean(prefix: String): Unit < (Async & Env[Workflow.Config] & Abort[StoreError]) =
    for
      cfg  <- Env.get[Workflow.Config]
      keys <- cfg.store.list(prefix)
      _    <- Kyo.foreach(keys)(cfg.store.remove)
    yield ()

  /** Internal carrier for CLI tokens, threaded through the scheduler so a
    * `Command.cli` body can pull them out of its [[CommandContext]].
    *
    * Public surface is intentionally tiny: only [[runCli]] populates a real
    * value here; everything else (plain [[run]] / [[runAll]]) uses the empty
    * default.
    */
  private[workflow] final case class CliTokens(raw: Chunk[String])
  private[workflow] object CliTokens:
    val empty: CliTokens = CliTokens(Chunk.empty[String])

  /** Run a single [[Command]] against a list of CLI tokens.
    *
    * Threads the tokens into the scheduler via the [[CliTokens]] Env so the
    * `Command.cli`-built body sees them on its `CommandContext.args.raw`.
    * Parse errors raised inside the body via
    * [[Command.CommandArgsParseException]] are caught and re-projected as a
    * typed [[CliParseError]] on the error channel.
    */
  def runCli[A](cmd: Command[A], tokens: Seq[String])(using
      Frame,
  ): A < (Async & Env[Workflow.Config] & Abort[WorkflowError | CliParseError]) =
    val carrier = CliTokens(Chunk.from(tokens))
    val raw     =
      Env.run(carrier)(Scheduler.executeWithTokens(cmd))
        : A < (Async & Env[Workflow.Config] & Abort[WorkflowError])
    Abort.recover[WorkflowError] {
      case WorkflowError.TaskFailed(_, message) if message.startsWith("CLI parse failed: ") =>
        Abort.fail[CliParseError](
          CliParseError.Failed(message.stripPrefix("CLI parse failed: "), usage = ""),
        )
      case other =>
        Abort.fail[WorkflowError](other)
    }(raw)
  end runCli
end Workflow
