package io.eleven19.kymora

import kyo.*

package object workflow:
  private[workflow] val LibraryVersion: String = "0.1.0-SNAPSHOT"
  private[workflow] val _kyoSmoke: Result[Nothing, String] = Result.succeed(LibraryVersion)
