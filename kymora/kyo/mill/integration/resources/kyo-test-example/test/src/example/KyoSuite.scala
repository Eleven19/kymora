package example

import kyo.test.*

class KyoSuite extends Test[Any]:
  "runs as documented" in {
    assert("kyo".reverse == "oyk")
  }
