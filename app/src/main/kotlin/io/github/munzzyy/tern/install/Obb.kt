package io.github.munzzyy.tern.install

import java.io.File

/**
 * An OBB file of an app found in its archive: the name it keeps in the app's OBB folder, and where
 * the gate unpacked it, which it does only for an installer that can put it in place.
 */
data class ObbFile(val name: String, val file: File?)

/** What became of the OBB files of an install. */
enum class ObbOutcome {
    /** Every file was written to the app's OBB folder. */
    PLACED,

    /** The install did not succeed, so nothing was written. */
    NOT_INSTALLED,

    /** This installer cannot write to the app's OBB folder. */
    CANNOT,
}

/** The names OBB files keep, and the folder they go to. */
object ObbNames {
    private const val MAX_NAME = 255

    /** True for an entry of an archive that is an OBB file, whatever its name holds. */
    fun isObb(entryName: String): Boolean = entryName.endsWith(".obb", ignoreCase = true)

    /**
     * The name the OBB file at [entryName] of an archive keeps in the app's folder: the last part of
     * its path, as Obtainium keeps it. Null for a name that could leave that folder or pass for
     * another: a backslash, a leading dot, a control or format character, or more than 255 bytes.
     */
    fun of(entryName: String): String? {
        val name = entryName.substringAfterLast('/')
        if (!isObb(name) || name.startsWith('.') || name.toByteArray(Charsets.UTF_8).size > MAX_NAME) return null
        if (name.any { it == '\\' || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) return null
        return name
    }

    /** The OBB folder of [packageName] in the shared storage of the user [userId]. */
    fun folder(userId: Int, packageName: String): String = "/storage/emulated/$userId/Android/obb/$packageName"
}
