package io.eleven19.kymora.workflow.macros

import scala.quoted.*
import io.eleven19.kymora.workflow.TaskVersion
import kyo.*

object TaskVersionMacros:

    def literal(literal: Expr[String])(using Quotes): Expr[TaskVersion] =
        import quotes.reflect.*
        literal.value match
            case Some(s) =>
                TaskVersion.parse(s) match
                    case Result.Success(v) =>
                        val ms = Expr(v.major)
                        val ns = Expr(v.minor)
                        val ps = Expr(v.patch)
                        '{ TaskVersion.of($ms, $ns, $ps) }
                    case Result.Failure(_) =>
                        report.errorAndAbort(
                            s"\"$s\" is not a valid TaskVersion (expected M.m.p with non-negative integers)"
                        )
                    case _ =>
                        report.errorAndAbort(s"Unexpected parse result for \"$s\"")
            case None =>
                report.errorAndAbort(
                    "TaskVersion(literal) requires a string literal at the call site."
                )
end TaskVersionMacros
