package io.eleven19.kymora.kyo.mill.compat

import _root_.mill.*
import _root_.mill.scalalib.*
import io.eleven19.kymora.kyo.mill.KyoMillDefaults

enum CompatBackend(val artifactSuffix: String) derives upickle.default.ReadWriter:
    case Kyo           extends CompatBackend("kyo")
    case Zio           extends CompatBackend("zio")
    case CatsEffect    extends CompatBackend("ce")
    case Future        extends CompatBackend("future")
    case Ox            extends CompatBackend("ox")
    case TwitterFuture extends CompatBackend("twitter-future")

enum CompatPlatform derives upickle.default.ReadWriter:
    case Jvm, Js, Native, Wasm

final case class CompatAxis(backend: CompatBackend, platform: CompatPlatform) derives upickle.default.ReadWriter

object CompatAxis:

    val builtIn: Set[CompatAxis] =
        val allPlatforms = Set(CompatPlatform.Jvm, CompatPlatform.Js, CompatPlatform.Native, CompatPlatform.Wasm)
        allPlatforms.map(CompatAxis(CompatBackend.Kyo, _)) ++
            allPlatforms.map(CompatAxis(CompatBackend.Zio, _)) ++
            allPlatforms.map(CompatAxis(CompatBackend.Future, _)) ++
            Set(
                CompatAxis(CompatBackend.CatsEffect, CompatPlatform.Jvm),
                CompatAxis(CompatBackend.CatsEffect, CompatPlatform.Js)
            ) ++
            Set(
                CompatAxis(CompatBackend.Ox, CompatPlatform.Jvm),
                CompatAxis(CompatBackend.TwitterFuture, CompatPlatform.Jvm)
            )

    def supported(backend: CompatBackend, platform: CompatPlatform): Boolean =
        builtIn.contains(CompatAxis(backend, platform))

/** Native Mill compat wiring.
  *
  * Consumers model backend/platform combinations with explicit Mill `Cross` modules and mix this trait into each
  * concrete cross member.
  */
trait KyoCompatModule extends ScalaModule:
    def kyoVersion: T[String] = Task(KyoMillDefaults.kyoVersion)
    def compatBackend: CompatBackend
    def compatPlatform: CompatPlatform

    def compatAxis: T[CompatAxis] = Task {
        val axis = CompatAxis(compatBackend, compatPlatform)
        if CompatAxis.builtIn.contains(axis) then axis
        else
            throw new IllegalArgumentException(
                s"Unsupported Kyo compat axis: backend=${compatBackend.toString}, platform=${compatPlatform.toString}"
            )
    }

    def kyoCompatArtifactName: T[String] = Task {
        s"kyo-compat-${compatAxis().backend.artifactSuffix}"
    }

    override def mvnDeps: T[Seq[Dep]] = Task {
        super.mvnDeps() ++ Seq(mvn"io.getkyo::${kyoCompatArtifactName()}::${kyoVersion()}")
    }
