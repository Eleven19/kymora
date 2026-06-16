package build

import mill.*
import mill.scalalib.JavaModule
import mill.scalalib.PublishModule
import mill.scalalib.SonatypeCentralPublishModule
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

trait PublishSupport extends PublishModule with SonatypeCentralPublishModule {
  this: JavaModule =>
  private val defaultPublishVersion = "0.1.0-SNAPSHOT"

  def publishVersion = Task.Input {
    sys.env.getOrElse("PUBLISH_VERSION", defaultPublishVersion)
  }

  def pomSettings = Task {
    PomSettings(
      description = "Kymora.",
      organization = "io.eleven19.kymora",
      url = "https://github.com/Eleven19/kymora",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("Eleven19", "kymora"),
      developers = Seq(
        Developer(
          id = "DamianReeves",
          name = "Damian Reeves",
          url = "https://github.com/DamianReeves"
        )
      )
    )
  }
}
