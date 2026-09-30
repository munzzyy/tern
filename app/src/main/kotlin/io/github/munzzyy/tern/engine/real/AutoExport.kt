package io.github.munzzyy.tern.engine.real

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.munzzyy.tern.data.KeptExportName
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.engine.ExportStatus
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.log.TernLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps one export of the list up to date: a moment after the list or an app's settings change,
 * the file is written again, in the folder the person picked or in Download/Tern. A copy that
 * lives somewhere else survives losing the phone, and it is the same file an import reads.
 */
internal class AutoExport(private val e: RealEngine, private val files: Files, private val text: (ExportFormat) -> String) {
    private val _status = MutableStateFlow<ExportStatus?>(null)
    val status: StateFlow<ExportStatus?> get() = _status.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        e.scope.launch(Dispatchers.IO) {
            e.store.configChanges.drop(1).debounce(QUIET_MS).collect { if (e.settings.value.autoExport) write() }
        }
        // A new name, format or content of the file is written at once, not at the next change of the list.
        e.scope.launch(Dispatchers.IO) {
            e.settings
                .map { listOf(it.keptExportName, it.keptExportFormat, it.exportInstalledOnly, it.exportSettings, it.exportFolder) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(QUIET_MS)
                .collect { if (e.settings.value.autoExport) write() }
        }
    }

    /** Writes now, and says how it went through [status]. */
    suspend fun write() = withContext(Dispatchers.IO) {
        val settings = e.settings.value
        val folder = settings.exportFolder
        val name = KeptExportName.of(settings.keptExportName, settings.keptExportFormat)
        _status.value = try {
            val body = text(settings.keptExportFormat)
            if (folder != null) writeInto(Uri.parse(folder), name, body) else files.keep(name, body)
            ExportStatus(e.nowMs(), null)
        } catch (ex: ProblemException) {
            ExportStatus(_status.value?.writtenAtMs, ex.problem.message)
        } catch (ex: IOException) {
            ExportStatus(_status.value?.writtenAtMs, e.texts.exportFailed(ex.message))
        } catch (ex: RuntimeException) {
            TernLog.w(TAG, "The kept export could not be written: ${ex.javaClass.simpleName}")
            ExportStatus(_status.value?.writtenAtMs, e.texts.exportFailed(ex.message))
        }
    }

    /** Keeps Android's grant of [folder] across restarts, and writes there at once. */
    suspend fun choose(folder: Uri) = withContext(Dispatchers.IO) {
        e.context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val previous = e.settings.value.exportFolder
        if (previous != null && previous != folder.toString()) release(Uri.parse(previous))
    }

    fun release(folder: Uri) {
        try {
            e.context.contentResolver.releasePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    /** The file of [name] in the picked folder, made if it is not there, written over if it is. */
    private fun writeInto(folder: Uri, name: String, body: String) {
        val resolver = e.context.contentResolver
        val tree = DocumentsContract.getTreeDocumentId(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, tree)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val existing = resolver.query(children, columns, null, null, null)?.use { rows ->
            var found: Uri? = null
            while (found == null && rows.moveToNext()) {
                if (rows.getString(1) == name) found = DocumentsContract.buildDocumentUriUsingTree(folder, rows.getString(0))
            }
            found
        }
        val target = existing ?: DocumentsContract.createDocument(resolver, parent, MIME, name) ?: throw IOException("The folder took no new file")
        val out = resolver.openOutputStream(target, "wt") ?: throw IOException("The folder opened no file")
        out.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        const val TAG = "TernAutoExport"
        const val MIME = "application/json"
        const val QUIET_MS = 2_000L
    }
}
