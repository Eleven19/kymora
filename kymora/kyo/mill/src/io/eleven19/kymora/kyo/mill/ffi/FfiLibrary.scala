package io.eleven19.kymora.kyo.mill.ffi

import upickle.default.*

/** One native library produced or linked by a Kyo FFI Mill module. */
final case class FfiLibrary(
    id: String,
    cSources: Seq[os.Path],
    cHeaders: Seq[os.Path] = Seq.empty,
    includeDirs: Seq[os.Path] = Seq.empty,
    libDirs: Seq[os.Path] = Seq.empty,
    linkLibs: Seq[String] = Seq.empty,
    linkLibsByOs: Map[String, Seq[String]] = Map.empty,
    cFlags: Seq[String] = Seq.empty,
    linkFlags: Seq[String] = Seq.empty,
    staticLink: Boolean = false,
    dependsOn: Seq[String] = Seq.empty
):

    def resolvedLinkLibs(os: String): Seq[String] =
        val key        = if os == "linux-musl" then "linux" else os
        val osSpecific = linkLibsByOs.getOrElse(key, Seq.empty)
        (linkLibs ++ osSpecific).distinct

object FfiLibrary:

    given ReadWriter[os.Path]    = readwriter[String].bimap[os.Path](_.toString, os.Path(_))
    given ReadWriter[FfiLibrary] = macroRW[FfiLibrary]

    def sort(libraries: Seq[FfiLibrary]): Seq[FfiLibrary] =
        if libraries.size <= 1 && libraries.forall(_.dependsOn.isEmpty) then libraries
        else
            val byId = libraries.map(library => library.id -> library).toMap
            if byId.size != libraries.size then
                val duplicates =
                    libraries.groupBy(_.id).collect { case (id, values) if values.size > 1 => id }.toSeq.sorted
                throw new IllegalArgumentException(
                    s"ffiLibraries has duplicate library ids: ${duplicates.mkString(", ")}"
                )

            libraries.foreach { library =>
                library.dependsOn.foreach { dependency =>
                    if !byId.contains(dependency) then
                        throw new IllegalArgumentException(
                            s"FfiLibrary '${library.id}' depends on unknown library id '$dependency'. Declared ids: ${libraries.map(_.id).mkString("[", ", ", "]")}"
                        )
                }
            }

            val inputIndex = libraries.zipWithIndex.map { case (library, index) => library.id -> index }.toMap
            val incoming   = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
            libraries.foreach(library => incoming(library.id) = scala.collection.mutable.Set(library.dependsOn*))
            val remaining = scala.collection.mutable.Set.from(libraries.map(_.id))
            val result    = scala.collection.mutable.ArrayBuffer.empty[FfiLibrary]

            while remaining.nonEmpty do
                val ready = remaining.filter(id => incoming(id).isEmpty).toSeq.sortBy(inputIndex)
                if ready.isEmpty then
                    val start = remaining.head
                    val seen  = scala.collection.mutable.LinkedHashSet.empty[String]
                    val path  = scala.collection.mutable.ArrayBuffer.empty[String]
                    var cur   = start
                    while !seen.contains(cur) do
                        seen += cur
                        path += cur
                        cur = incoming(cur).headOption.getOrElse(start)
                    path += cur
                    throw new IllegalArgumentException(s"FfiLibrary.dependsOn has a cycle: ${path.mkString(" -> ")}")

                ready.foreach { id =>
                    result += byId(id)
                    remaining -= id
                    incoming.values.foreach(_ -= id)
                }

            result.toSeq
