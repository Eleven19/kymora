package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import io.eleven19.kymora.vfs.Vfs
import kyo.*
import kyo.test.*

class VfsPathRefTests extends Test[Any]:
  "render produces vref:v0c:<fp>:<path> for content hash" in {
    val r = VfsPathRef(VPath("a/b/c"), Fingerprint.unsafe("blake3:abcd"), quick = false)
    assert(VfsPathRef.render(r) == "vref:v0c:blake3:abcd:a/b/c")
  }
  "render produces vref:v0q:... for quick hash" in {
    val r = VfsPathRef(VPath("x"), Fingerprint.unsafe("blake3:1"), quick = true)
    assert(VfsPathRef.render(r) == "vref:v0q:blake3:1:x")
  }
  "parse round-trips render" in {
    val r = VfsPathRef(VPath("a/b/c"), Fingerprint.unsafe("blake3:abcd"), quick = false)
    val s = VfsPathRef.render(r)
    assert(VfsPathRef.parse(s) == Result.succeed(r))
  }
  "parse preserves colons in path component" in {
    val r = VfsPathRef.parse("vref:v0c:blake3:a:dir:with:colons")
    assert(r.toMaybe.exists(_.path.show == "dir:with:colons"))
  }
  "parse fails on unknown tag" in {
    val r = VfsPathRef.parse("vref:v0z:blake3:a:b")
    assert(r == Result.fail(ParseError.UnknownVfsPathRefTag("v0z")))
  }
  "Schema encodes as single string" in {
    val r = VfsPathRef(VPath("a"), Fingerprint.unsafe("blake3:b"), quick = false)
    val s = summon[Schema[VfsPathRef]].encodeString[Json](r)
    assert(s == "\"vref:v0c:blake3:b:a\"")
  }
  "of(path) hashes file contents" in {
    val path = VPath.root / "etc" / "hello.txt"
    for
      fs <- Vfs.inMemory.init
      _  <- fs.write(path, "hello\n")
      r  <- VfsPathRef.of(path, fs)
    yield
      assert(!r.quick)
      assert(r.path == path)
      assert(r.fingerprint.value.startsWith("blake3:"))
  }
  "quick(path) sets the quick flag" in {
    val path = VPath.root / "tmp" / "data.bin"
    for
      fs <- Vfs.inMemory.init
      _  <- fs.write(path, "anything")
      r  <- VfsPathRef.quick(path, fs)
    yield assert(r.quick)
  }
  "two paths with identical content produce equal fingerprints (content mode)" in {
    val pa = VPath.root / "a" / "x.txt"
    val pb = VPath.root / "b" / "y.txt"
    for
      fs <- Vfs.inMemory.init
      _  <- fs.write(pa, "same")
      _  <- fs.write(pb, "same")
      a  <- VfsPathRef.of(pa, fs)
      b  <- VfsPathRef.of(pb, fs)
    yield assert(a.fingerprint == b.fingerprint)
  }
end VfsPathRefTests
