package io.github.munzzyy.stamp.core.apk

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonArray
import io.github.munzzyy.stamp.core.json.JsonObject
import io.github.munzzyy.stamp.core.testing.Fixtures
import java.io.File
import java.nio.file.Files

/** The files of tools/make-test-apks.sh signing, and what apksigner said about each when they were made. */
object SigningFixtures {
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    /** The platform versions apksigner was asked about. */
    val levels = listOf(24, 27, 28, 29, 30, 32, 33, 36)

    val expected: JsonObject by lazy { Json.parseObject(Fixtures.text("signing/expected.json")) }

    val names: List<String> by lazy { expected.fields.keys.sorted() }

    private val folder: File by lazy { Files.createTempDirectory("signing-fixtures").toFile().apply { deleteOnExit() } }

    fun bytes(name: String): ByteArray = Fixtures.bytes("signing/$name")

    /** The certificates apksigner goes by on a device of [sdk], or null where it refuses the file. */
    fun certificatesAt(name: String, sdk: Int): Set<String>? =
        (expected.obj(name)!!.obj("bySdk")!!.fields.getValue(sdk.toString()) as? JsonArray)?.strings()?.toSet()

    fun lineage(name: String): List<String> = expected.obj(name)!!.array("lineage")!!.strings()

    fun file(bytes: ByteArray): File =
        File.createTempFile("apk", ".apk", folder).apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    fun verify(bytes: ByteArray, sdk: Int): SignatureVerdict {
        val file = file(bytes)
        try {
            return ApkVerifier.verify(file, sdk, MAX_BYTES)
        } finally {
            file.delete()
        }
    }
}
