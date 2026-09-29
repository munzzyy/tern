package io.github.munzzyy.jackdaw.core.apk

import io.github.munzzyy.jackdaw.core.apk.CompiledXml.Node
import io.github.munzzyy.jackdaw.core.apk.CompiledXml.bool
import io.github.munzzyy.jackdaw.core.apk.CompiledXml.int
import io.github.munzzyy.jackdaw.core.apk.CompiledXml.ref
import io.github.munzzyy.jackdaw.core.apk.CompiledXml.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryManifestTest {
    private val versionCode = 16843291
    private val versionName = 16843292
    private val versionCodeMajor = 16844150
    private val minSdk = 16843276
    private val targetSdk = 16843376
    private val name = 16842755
    private val debuggable = 16842767
    private val testOnly = 16843378
    private val isFeatureSplit = 16844123

    private fun manifest(vararg attrs: CompiledXml.Attr, stripNames: Boolean = false, children: List<Node> = defaultChildren(stripNames)) =
        Node("manifest", listOf(str("package", "com.example.app", namespace = null)) + attrs.map { if (stripNames) strip(it) else it }, children)

    private fun strip(a: CompiledXml.Attr) = if (a.id == 0) a else CompiledXml.Attr(a.namespace, "", a.id, a.type, a.data, a.text)

    private fun defaultChildren(stripNames: Boolean): List<Node> {
        fun s(a: CompiledXml.Attr) = if (stripNames) strip(a) else a
        return listOf(
            Node("uses-sdk", listOf(s(int("minSdkVersion", 23, minSdk)), s(int("targetSdkVersion", 34, targetSdk)))),
            Node("uses-permission", listOf(s(str("name", "android.permission.INTERNET", name)))),
            Node("uses-permission-sdk-23", listOf(s(str("name", "android.permission.CAMERA", name)))),
            Node("uses-feature", listOf(s(str("name", "android.hardware.nfc", name)))),
            Node("application", listOf(s(bool("debuggable", true, debuggable)), s(bool("testOnly", true, testOnly)))),
        )
    }

    private fun parse(root: Node, utf8: Boolean = false, resourceMap: Boolean = true) =
        BinaryManifest.parse(CompiledXml.compile(root, utf8, resourceMap))

    @Test
    fun readsEveryFieldFromUtf16AndUtf8Pools() {
        val root = manifest(int("versionCode", 42, versionCode), str("versionName", "4.2", versionName))
        for (utf8 in listOf(false, true)) {
            val m = parse(root, utf8)
            assertEquals("com.example.app", m.packageName)
            assertEquals(42L, m.versionCode)
            assertEquals("4.2", m.versionName)
            assertEquals(23, m.minSdk)
            assertEquals(34, m.targetSdk)
            assertEquals(listOf("android.permission.INTERNET", "android.permission.CAMERA"), m.permissions)
            assertEquals(listOf("android.hardware.nfc"), m.features)
            assertTrue(m.debuggable)
            assertTrue(m.testOnly)
            assertNull(m.split)
        }
    }

    @Test
    fun strippedAttributeNamesResolveByResourceId() {
        val m = parse(manifest(int("versionCode", 7, versionCode), str("versionName", "7.0", versionName), stripNames = true))
        assertEquals(7L, m.versionCode)
        assertEquals("7.0", m.versionName)
        assertEquals(23, m.minSdk)
        assertEquals(listOf("android.permission.INTERNET", "android.permission.CAMERA"), m.permissions)
    }

    @Test
    fun resourceIdWinsOverAMisleadingName() {
        val m = parse(manifest(int("versionName", 9, versionCode), str("versionCode", "decoy", versionName)))
        assertEquals(9L, m.versionCode)
        assertEquals("decoy", m.versionName)
    }

    @Test
    fun namesAreTheFallbackWithoutAResourceMap() {
        val m = parse(manifest(int("versionCode", 5, versionCode), str("versionName", "5.0", versionName)), resourceMap = false)
        assertEquals(5L, m.versionCode)
        assertEquals("5.0", m.versionName)
        assertEquals(34, m.targetSdk)
    }

    @Test
    fun versionCodeMajorFillsTheHighBits() {
        val m = parse(manifest(int("versionCode", -1, versionCode), int("versionCodeMajor", 3, versionCodeMajor)))
        assertEquals((3L shl 32) or 0xffffffffL, m.versionCode)
    }

    @Test
    fun versionNameReferenceBecomesNull() {
        assertNull(parse(manifest(int("versionCode", 1, versionCode), ref("versionName", versionName))).versionName)
    }

    @Test
    fun splitAttributesAreRead() {
        val m = parse(
            manifest(
                int("versionCode", 1, versionCode),
                str("split", "dyn.config.x86_64", namespace = null),
                str("configForSplit", "dyn", namespace = null),
                bool("isFeatureSplit", true, isFeatureSplit),
                children = emptyList(),
            ),
        )
        assertEquals("dyn.config.x86_64", m.split)
        assertEquals("dyn", m.configForSplit)
        assertTrue(m.isFeatureSplit)
        assertFalse(m.debuggable)
        assertNull(m.minSdk)
    }

    @Test
    fun duplicateResourceIdIsRefused() {
        val root = manifest(int("versionCode", 1, versionCode), int("versionCode", 2, versionCode))
        assertThrows(ApkFormatException::class.java) { parse(root) }
    }

    @Test
    fun rootMustBeManifestAndCarryAValidPackage() {
        assertThrows(ApkFormatException::class.java) { parse(Node("application", emptyList())) }
        for (bad in listOf("app", "1com.example", "com..example", "com.ex-ample", "com._x", ".com.x", "com.x.", "a." + "b".repeat(254))) {
            val root = Node("manifest", listOf(str("package", bad, namespace = null)))
            assertThrows(bad, ApkFormatException::class.java) { parse(root) }
        }
    }

    @Test
    fun packageNameRules() {
        assertTrue(BinaryManifest.isValidName("com.example.app"))
        assertTrue(BinaryManifest.isValidName("a.b_1.C9"))
        assertTrue(BinaryManifest.isValidName("a." + "b".repeat(253)))
        assertFalse(BinaryManifest.isValidName("a." + "b".repeat(254)))
        assertFalse(BinaryManifest.isValidName("com"))
        assertTrue(BinaryManifest.isValidName("config", minSegments = 1))
        assertFalse(BinaryManifest.isValidName("com.é.app"))
    }

    @Test
    fun everyTruncationFailsCleanly() {
        val bytes = CompiledXml.compile(manifest(int("versionCode", 1, versionCode)))
        for (length in 0 until bytes.size) {
            val failure = runCatching { BinaryManifest.parse(bytes.copyOf(length)) }.exceptionOrNull()
            assertTrue("length $length threw $failure", failure == null || failure is ApkFormatException)
        }
    }

    @Test
    fun notCompiledXmlIsRefused() {
        assertThrows(ApkFormatException::class.java) { BinaryManifest.parse("<manifest/>".toByteArray()) }
        assertThrows(ApkFormatException::class.java) { BinaryManifest.parse(ByteArray(0)) }
    }
}
