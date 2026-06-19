package io.eleven19.kymora.workflow.macros

import scala.quoted.*
import io.eleven19.kymora.workflow.TaskScope
import io.eleven19.kymora.workflow.internal.Validation
import kyo.*

object TaskScopeMacros:

    def literal(literal: Expr[String])(using Quotes): Expr[TaskScope] =
        import quotes.reflect.*
        literal.value match
            case Some(s) =>
                Validation.check(s) match
                    case Result.Success(_) => '{ TaskScope.unsafe($literal) }
                    case Result.Failure(reason) =>
                        report.errorAndAbort(s"\"$s\" is not a valid TaskScope: $reason")
                    case _ =>
                        report.errorAndAbort(s"\"$s\" is not a valid TaskScope")
            case None =>
                report.errorAndAbort(
                    "TaskScope(literal) requires a string literal at the call site. " +
                        "For dynamic input use TaskScope.parse and handle the Result."
                )
end TaskScopeMacros
