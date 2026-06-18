package io.eleven19.kymora.workflow

/** A task with its result type hidden behind a path-dependent type member.
  *
  * `AnyTask` is useful for workflow internals and APIs that need heterogeneous task collections without exposing
  * wildcard syntax such as `Task[?]`.
  *
  * {{{
  * val tasks = Seq(
  *   AnyTask(Task.input("scalaVersion")("3.8.4")),
  *   AnyTask(Task.cached("answer")(42)),
  * )
  *
  * val first: Task[tasks.head.ResultType] =
  *   tasks.head.asTask
  * }}}
  */
opaque type AnyTask = Task[?]

object AnyTask:

    /** Wrap a task while preserving its result type as `ResultType`. */
    def apply[A](task: Task[A]): AnyTask { type ResultType = A } =
        task.asInstanceOf[AnyTask { type ResultType = A }]

    extension (task: AnyTask)
        /** The wrapped task id. */
        def id: TaskId = task.asInstanceOf[Task[?]].id

        /** The wrapped task version. */
        def version: TaskVersion = task.asInstanceOf[Task[?]].version

        /** Recover the wrapped task with this value's path-dependent result type. */
        def asTask: Task[task.ResultType] =
            task.asInstanceOf[Task[task.ResultType]]

        private[workflow] def unsafeTask: Task[?] =
            task.asInstanceOf[Task[?]]
end AnyTask
