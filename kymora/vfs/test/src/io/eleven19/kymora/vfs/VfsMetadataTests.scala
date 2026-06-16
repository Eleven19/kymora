package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VfsMetadataTests extends Test[Any]:
    "renders byte sizes with useful units" in {
        assert(VfsSize.bytes(512).render == "512 B")
        assert(VfsSize.bytes(2048).render == "2 KB")
        assert(VfsSize.bytes(1536 * 1024).render == "1.5 MB")
    }

    "renders timestamps in UTC ISO-8601" in {
        assert(VfsTimestamp.epochMillis(1781602200000L).render == "2026-06-16T09:30:00Z")
    }

    "stores stat metadata using opaque domain types" in {
        val stat = VfsStat(VfsEntryType.File, VfsSize.bytes(42), VfsTimestamp.epochMillis(1000L))
        assert(stat.entryType == VfsEntryType.File)
        assert(stat.size.toBytes == 42L)
        assert(stat.lastModified.toEpochMillis == 1000L)
    }
end VfsMetadataTests
