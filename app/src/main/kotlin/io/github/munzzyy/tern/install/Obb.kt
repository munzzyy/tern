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

    /** What OBB files are named of, as main.12.org.example.app.obb: nothing a shell reads as more than a word. */
    private val NAME = Regex("[A-Za-z0-9._+-]+")

    /** True for an entry of an archive that is an OBB file, whatever its name holds. */
    fun isObb(entryName: String): Boolean = entryName.endsWith(".obb", ignoreCase = true)

    /**
     * The name the OBB file at [entryName] of an archive keeps in the app's folder: the last part of
     * its path, as Obtainium keeps it. Null for any name but letters, digits and . _ + -, for one
     * that starts with a dot, and for one of more than 255 characters: such a name could leave that
     * folder, pass for another, or mean more to the shell that writes it than a file name.
     */
    fun of(entryName: String): String? {
        val name = entryName.substringAfterLast('/')
        if (!isObb(name) || name.startsWith('.') || name.length > MAX_NAME) return null
        return name.takeIf { NAME.matches(it) }
    }

    /** The OBB folder of [packageName] in the shared storage of the user [userId]. */
    fun folder(userId: Int, packageName: String): String = "/storage/emulated/$userId/Android/obb/$packageName"
}
