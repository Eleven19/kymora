package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VPathTests extends Test[Any]:
    "constructs absolute and relative unix-style paths" in {
        assert((VPath.root / "etc" / "kymora.conf").show == "/etc/kymora.conf")
        assert((VPath.cwd / "src" / "main.scala").show == "src/main.scala")
    }

    "normalizes dot and dotdot without escaping root" in {
        for
            app <- VPath.parse("/app/./config/../data")
            etc <- VPath.parse("/../../etc")
        yield
            assert(app.show == "/app/data")
            assert(etc.show == "/etc")
    }

    "preserves leading dotdot in relative paths" in {
        for
            path     <- VPath.parse("../target")
            resolved <- path.resolveAgainst(VPath.root / "workspace" / "app")
        yield
            assert(path.show == "../target")
            assert(resolved.show == "/workspace/target")
    }

    "preserves case sensitivity" in {
        for
            mixed <- VPath.parse("/Readme.md")
            upper <- VPath.parse("/README.md")
        yield assert(mixed != upper)
    }

    "treats tilde as literal without context" in {
        VPath.parse("~/config").map(path => assert(path.show == "~/config"))
    }

    "expands tilde and cwd with context" in {
        val ctx = VPathContext(
            home = Maybe(VPath.root / "home" / "damian"),
            cwd = VPath.root / "workspace" / "kymora"
        )
        for
            home <- VPath.parse("~/config", ctx)
            cwd <- VPath.parse("src/Vfs.scala", ctx)
        yield
            assert(home.show == "/home/damian/config")
            assert(cwd.show == "/workspace/kymora/src/Vfs.scala")
    }

    "rejects tilde expansion when context has no home" in {
        Abort.run[VfsError](VPath.parse("~/config", VPathContext())).map { result =>
            assert(result.isFailure)
            assert(result.failure.toOption.exists(_.isInstanceOf[VfsError.NoHomeDirectory]))
        }
    }

    "resolves relative paths against absolute base" in {
        val base = VPath.root / "workspace" / "kymora"
        (VPath.cwd / "docs" / ".." / "README.md")
            .resolveAgainst(base)
            .map(path => assert(path.show == "/workspace/kymora/README.md"))
    }
end VPathTests
