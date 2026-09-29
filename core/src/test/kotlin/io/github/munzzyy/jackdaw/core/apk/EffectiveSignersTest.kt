package io.github.munzzyy.jackdaw.core.apk

import io.github.munzzyy.jackdaw.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveSignersTest {
    private fun inspect(name: String) = ApkInspector.inspect(BytesSource(Fixtures.bytes("apk/$name")))

    @Test
    fun aRotatedKeyCountsOnlyWhereAndroidHonoursIt() {
        val info = inspect("app-rotated.apk")
        val original = "eef5b9ce5894133be26265ab43801b2e142e846f4be056d13f0484bcedcc0e63"
        val rotated = "b09831fa62fc9415839a8d1bd7e6b32c607bf8fccd64a0893f5dba368ee62c86"

        val modern = info.signersFor(36)
        assertEquals(listOf(rotated), modern.map { it.sha256 })
        assertEquals(SignatureScheme.V31, modern.single().scheme)
        assertEquals(listOf(original, rotated), modern.single().lineage)

        val older = info.signersFor(30)
        assertEquals(listOf(original), older.map { it.sha256 })
        assertEquals(SignatureScheme.V3, older.single().scheme)
    }

    @Test
    fun theNewestSchemePresentWins() {
        assertEquals(setOf(SignatureScheme.V3), inspect("app-v1.apk").signersFor(36).map { it.scheme }.toSet())
        assertEquals(setOf(SignatureScheme.V2), inspect("app-v2-only.apk").signersFor(36).map { it.scheme }.toSet())
        assertEquals(setOf(SignatureScheme.V1), inspect("app-jar-only.apk").signersFor(36).map { it.scheme }.toSet())
    }

    @Test
    fun aCertificateNamedOnlyInAnOlderBlockDoesNotCount() {
        val info = inspect("app-v1.apk")
        val real = info.signersFor(36).single()
        val planted = SignerInfo(SignatureScheme.V2, "f".repeat(64), null, emptyList())
        val forged = info.copy(signers = info.signers + planted)
        assertEquals(listOf(real.sha256), forged.signersFor(36).map { it.sha256 })
        assertTrue(forged.signers.any { it.sha256 == planted.sha256 })
    }

    @Test
    fun aSignerOutsideItsPlatformRangeIsSkipped() {
        val info = inspect("app-v1.apk")
        val v3 = info.signers.first { it.scheme == SignatureScheme.V3 }
        val narrowed = info.copy(signers = info.signers.map { if (it === v3) it.copy(minSdk = 40) else it })
        assertEquals(setOf(SignatureScheme.V2), narrowed.signersFor(36).map { it.scheme }.toSet())
    }
}
