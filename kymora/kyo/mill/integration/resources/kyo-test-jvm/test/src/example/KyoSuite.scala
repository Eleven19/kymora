package example

import kyo.test.*

class KyoSuite extends Test[Any]:
  "runs through the downstream KyoTestModule trait" in {
    assert(40 + 2 == 42)
  }
