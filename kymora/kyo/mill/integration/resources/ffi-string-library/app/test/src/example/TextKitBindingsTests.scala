package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class TextKitBindingsTests extends Test[Any]:
  "example string library passes realistic text values through Kyo FFI" in {
    import AllowUnsafe.embrace.danger

    val textkit = Ffi.load[TextKitBindings]
    assert(textkit.textkitCountWords("build tools should feel native") == 5)
    assert(textkit.textkitHasPrefix("kymora workflow", "kymora"))
    assert(textkit.textkitSharedPrefixLength("workflow", "workbench") == 4)
    assert(textkit.textkitScoreTitle("kymora workflow effects", "kymora") == 3.6)
  }
