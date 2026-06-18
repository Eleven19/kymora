package io.eleven19.kymora.kyo.mill

/** Defaults used by the Kymora Kyo Mill integration traits.
  *
  * Downstream builds can override the relevant `def`s on each module trait when they need to pin a different Kyo,
  * Scala.js, or Scala Native version.
  */
object KyoMillDefaults:
    val kyoVersion: String         = "1.0.0-RC4"
    val scalaJSVersion: String     = "1.21.0"
    val scalaNativeVersion: String = "0.5.11"
