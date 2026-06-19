package io.eleven19.kymora.workflow.macros

import scala.quoted.*
import io.eleven19.kymora.workflow.TaskId
import io.eleven19.kymora.workflow.internal.Validation
import kyo.*

object TaskIdMacros:

    def literal(literal: Expr[String])(using Quotes): Expr[TaskId] =
        import quotes.reflect.*
        literal.value match
            case Some(s) =>
                Validation.check(s) match
                    case Result.Success(_) => '{ TaskId.unsafe($literal) }
                    case Result.Failure(reason) =>
                        report.errorAndAbort(s"\"$s\" is not a valid TaskId: $reason")
                    case _ =>
                        report.errorAndAbort(s"\"$s\" is not a valid TaskId")
            case None =>
                report.errorAndAbort(
                    "TaskId(literal) requires a string literal at the call site. " +
                        "For dynamic input use TaskId.parse and handle the Result."
                )
end TaskIdMacros
